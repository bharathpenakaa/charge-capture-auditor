import java.util.*;
import java.util.stream.*;

/**
 * Charge Capture Auditor
 * ----------------------
 * Compares documented services (what was done) against billed charges
 * (what was charged) for each encounter and flags:
 *   - MISSED_CHARGE        : documented but never billed (revenue leakage)
 *   - UNSUPPORTED_CHARGE   : billed but not documented (compliance risk)
 *   - DUPLICATE_CHARGE     : same CPT billed on multiple lines
 *   - UNIT_MISMATCH        : billed units differ from documented units
 *   - UNBUNDLING           : component code billed with its comprehensive code
 *   - MUTUALLY_EXCLUSIVE   : codes that should not appear together (e.g., two E/M levels)
 *   - AMOUNT_OUTLIER       : billed amount deviates from the fee schedule
 *
 * Requires Java 17+. No external dependencies.
 * Compile: javac ChargeCaptureAuditor.java
 * Run:     java ChargeCaptureAuditor
 */
public class ChargeCaptureAuditor {

    // ---------- Model ----------

    enum Type { MISSED_CHARGE, UNSUPPORTED_CHARGE, DUPLICATE_CHARGE, UNIT_MISMATCH,
                UNBUNDLING, MUTUALLY_EXCLUSIVE, AMOUNT_OUTLIER }

    enum Severity { LOW, MEDIUM, HIGH }

    record Service(String encounterId, String cpt, int units) {}

    record Charge(String encounterId, String cpt, int units, double amount) {}

    /** leakage = true means revenue not captured; false means overbilling / compliance exposure. */
    record Finding(String encounterId, Type type, Severity severity, String cpt,
                   double dollars, boolean leakage, String detail) {}

    // ---------- Reference data (replace with your payer/contract data) ----------

    static final Map<String, Double> FEE_SCHEDULE = Map.of(
            "99213", 120.0, "99214", 180.0, "80053", 45.0, "80048", 35.0,
            "71046", 90.0, "93000", 55.0, "36415", 15.0, "85025", 25.0);

    /** component code -> comprehensive code that already includes it */
    static final Map<String, String> BUNDLED_INTO = Map.of("80048", "80053");

    /** sets of codes that must not be billed together on one encounter */
    static final List<Set<String>> MUTUALLY_EXCLUSIVE = List.of(Set.of("99213", "99214"));

    static final double OUTLIER_TOLERANCE = 0.25; // 25% from fee schedule

    static double fee(String cpt) { return FEE_SCHEDULE.getOrDefault(cpt, 0.0); }

    // ---------- Audit engine ----------

    static List<Finding> audit(List<Service> services, List<Charge> charges) {
        Map<String, Map<String, Integer>> documented = new TreeMap<>();
        for (Service s : services) {
            documented.computeIfAbsent(s.encounterId(), k -> new TreeMap<>())
                      .merge(s.cpt(), s.units(), Integer::sum);
        }
        Map<String, List<Charge>> billed = charges.stream()
                .collect(Collectors.groupingBy(Charge::encounterId));

        Set<String> encounters = new TreeSet<>(documented.keySet());
        encounters.addAll(billed.keySet());

        List<Finding> findings = new ArrayList<>();

        for (String enc : encounters) {
            Map<String, Integer> doc = documented.getOrDefault(enc, Map.of());
            Map<String, List<Charge>> byCpt = billed.getOrDefault(enc, List.of()).stream()
                    .collect(Collectors.groupingBy(Charge::cpt, TreeMap::new, Collectors.toList()));

            // 1. Missed charges
            doc.forEach((cpt, units) -> {
                if (!byCpt.containsKey(cpt)) {
                    double lost = fee(cpt) * units;
                    findings.add(new Finding(enc, Type.MISSED_CHARGE,
                            lost >= 100 ? Severity.HIGH : Severity.MEDIUM, cpt, lost, true,
                            "Documented x" + units + " but no charge found"));
                }
            });

            // 2. Per billed CPT checks
            byCpt.forEach((cpt, lines) -> {
                int billedUnits = lines.stream().mapToInt(Charge::units).sum();
                double billedAmount = lines.stream().mapToDouble(Charge::amount).sum();
                Integer docUnits = doc.get(cpt);

                if (docUnits == null) {
                    findings.add(new Finding(enc, Type.UNSUPPORTED_CHARGE, Severity.HIGH, cpt,
                            billedAmount, false, "Billed with no supporting documentation"));
                } else if (lines.size() > 1) {
                    double extra = billedAmount - lines.get(0).amount();
                    findings.add(new Finding(enc, Type.DUPLICATE_CHARGE, Severity.MEDIUM, cpt,
                            extra, false, "Billed on " + lines.size() + " separate lines"));
                } else if (billedUnits != docUnits) {
                    double diff = fee(cpt) * (billedUnits - docUnits);
                    findings.add(new Finding(enc, Type.UNIT_MISMATCH, Severity.MEDIUM, cpt,
                            Math.abs(diff), diff < 0,
                            "Documented " + docUnits + " unit(s), billed " + billedUnits));
                }

                for (Charge c : lines) {
                    double expected = fee(cpt) * c.units();
                    if (expected > 0) {
                        double deviation = (c.amount() - expected) / expected;
                        if (Math.abs(deviation) > OUTLIER_TOLERANCE) {
                            findings.add(new Finding(enc, Type.AMOUNT_OUTLIER, Severity.MEDIUM, cpt,
                                    Math.abs(c.amount() - expected), deviation < 0,
                                    String.format("Billed $%.2f vs expected $%.2f (%+.0f%%)",
                                            c.amount(), expected, deviation * 100)));
                        }
                    }
                }
            });

            // 3. Unbundling
            BUNDLED_INTO.forEach((component, comprehensive) -> {
                if (byCpt.containsKey(component) && byCpt.containsKey(comprehensive)) {
                    double amt = byCpt.get(component).stream().mapToDouble(Charge::amount).sum();
                    findings.add(new Finding(enc, Type.UNBUNDLING, Severity.HIGH, component, amt, false,
                            component + " is included in " + comprehensive));
                }
            });

            // 4. Mutually exclusive codes
            for (Set<String> group : MUTUALLY_EXCLUSIVE) {
                List<String> present = group.stream().filter(byCpt::containsKey).sorted().toList();
                if (present.size() > 1) {
                    double cheapest = present.stream().mapToDouble(ChargeCaptureAuditor::fee).min().orElse(0);
                    findings.add(new Finding(enc, Type.MUTUALLY_EXCLUSIVE, Severity.HIGH,
                            String.join("+", present), cheapest, false,
                            "Codes cannot be billed together: " + present));
                }
            }
        }
        return findings;
    }

    // ---------- Capture rate ----------

    /** Value of documented services that were actually billed / total value of documented services. */
    static double captureRate(List<Service> services, List<Charge> charges) {
        Map<String, Integer> billedUnits = new HashMap<>();
        for (Charge c : charges) billedUnits.merge(c.encounterId() + "|" + c.cpt(), c.units(), Integer::sum);

        double documentedValue = 0, capturedValue = 0;
        for (Service s : services) {
            documentedValue += fee(s.cpt()) * s.units();
            int b = billedUnits.getOrDefault(s.encounterId() + "|" + s.cpt(), 0);
            capturedValue += fee(s.cpt()) * Math.min(s.units(), b);
        }
        return documentedValue == 0 ? 1.0 : capturedValue / documentedValue;
    }

    // ---------- Report ----------

    static void report(List<Finding> findings, double captureRate) {
        Map<String, List<Finding>> byEncounter = findings.stream()
                .collect(Collectors.groupingBy(Finding::encounterId, TreeMap::new, Collectors.toList()));

        byEncounter.forEach((enc, list) -> {
            System.out.printf("%nEncounter %s (%d finding(s))%n", enc, list.size());
            list.stream()
                .sorted(Comparator.comparing(Finding::severity).reversed())
                .forEach(f -> System.out.printf("  [%-6s] %-18s %-11s $%8.2f  %s%n",
                        f.severity(), f.type(), f.cpt(), f.dollars(), f.detail()));
        });

        double leakage = findings.stream().filter(Finding::leakage).mapToDouble(Finding::dollars).sum();
        double exposure = findings.stream().filter(f -> !f.leakage()).mapToDouble(Finding::dollars).sum();

        System.out.println("\n=== Summary ===");
        Arrays.stream(Type.values()).forEach(t -> {
            long n = findings.stream().filter(f -> f.type() == t).count();
            if (n > 0) System.out.printf("  %-20s : %d%n", t, n);
        });
        System.out.printf("  Revenue leakage (recoverable)  : $%.2f%n", leakage);
        System.out.printf("  Compliance exposure (overbilled): $%.2f%n", exposure);
        System.out.printf("  Charge capture rate            : %.1f%%%n", captureRate * 100);
    }

    // ---------- Demo ----------

    public static void main(String[] args) {
        List<Service> services = List.of(
                new Service("E100", "99214", 1), new Service("E100", "80053", 1), new Service("E100", "36415", 1),
                new Service("E101", "99213", 1), new Service("E101", "93000", 1), new Service("E101", "71046", 1),
                new Service("E102", "99213", 1), new Service("E102", "85025", 1),
                new Service("E103", "99214", 1), new Service("E103", "80053", 1), new Service("E103", "80048", 1),
                new Service("E104", "99213", 1), new Service("E104", "99214", 1));

        List<Charge> charges = List.of(
                new Charge("E100", "99214", 1, 180.0), new Charge("E100", "80053", 1, 45.0),               // 36415 missed
                new Charge("E101", "99213", 1, 120.0), new Charge("E101", "93000", 1, 55.0),
                new Charge("E101", "71046", 1, 90.0), new Charge("E101", "71046", 1, 90.0),               // duplicate
                new Charge("E102", "99213", 1, 120.0), new Charge("E102", "85025", 2, 50.0),              // unit mismatch
                new Charge("E102", "93000", 1, 55.0),                                                      // unsupported
                new Charge("E103", "99214", 1, 240.0), new Charge("E103", "80053", 1, 45.0),              // outlier
                new Charge("E103", "80048", 1, 35.0),                                                      // unbundling
                new Charge("E104", "99213", 1, 120.0), new Charge("E104", "99214", 1, 180.0));            // exclusive

        List<Finding> findings = audit(services, charges);
        report(findings, captureRate(services, charges));
    }
}
