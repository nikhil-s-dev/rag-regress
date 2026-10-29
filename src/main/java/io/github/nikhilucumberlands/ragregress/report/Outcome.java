package io.github.nikhilucumberlands.ragregress.report;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.compare.Comparison;
import io.github.nikhilucumberlands.ragregress.compare.MetricDelta;
import io.github.nikhilucumberlands.ragregress.gate.Threshold.ThresholdResult;

import java.util.ArrayList;
import java.util.List;

/**
 * An evaluation plus the checks applied to it. Passes when every threshold holds, no gated metric
 * regressed against the baseline and at least one query was retrieved successfully.
 *
 * @param comparison null when no baseline was given
 */
public record Outcome(EvalReport report, List<ThresholdResult> thresholds, Comparison comparison) {

    public Outcome {
        thresholds = List.copyOf(thresholds);
    }

    public boolean passed() {
        return failures().isEmpty();
    }

    /** Human-readable reasons the check failed, empty when it passed. */
    public List<String> failures() {
        List<String> reasons = new ArrayList<>();
        if (report.errors() == report.size()) {
            reasons.add("every query failed to retrieve");
        }
        long failedThresholds = thresholds.stream().filter(t -> !t.passed()).count();
        if (failedThresholds > 0) {
            reasons.add(failedThresholds + " of " + thresholds.size() + " thresholds failed");
        }
        if (comparison != null && comparison.hasRegression()) {
            reasons.add(describeRegressions(comparison.regressions()));
        }
        return reasons;
    }

    /** "3 metrics regressed significantly (recall@5, ndcg@10, mrr@10)", shortened past four names. */
    public static String describeRegressions(List<MetricDelta> regressions) {
        List<String> names = regressions.stream().map(MetricDelta::metric).toList();
        String listed = String.join(", ", names.subList(0, Math.min(4, names.size())));
        if (names.size() > 4) {
            listed += " and " + (names.size() - 4) + " more";
        }
        return names.size() + (names.size() == 1 ? " metric" : " metrics") + " regressed significantly (" + listed + ")";
    }
}
