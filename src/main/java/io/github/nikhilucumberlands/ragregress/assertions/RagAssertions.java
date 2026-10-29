package io.github.nikhilucumberlands.ragregress.assertions;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.QueryResult;
import io.github.nikhilucumberlands.ragregress.compare.Comparison;
import io.github.nikhilucumberlands.ragregress.compare.MetricDelta;
import io.github.nikhilucumberlands.ragregress.compare.QueryChange;
import io.github.nikhilucumberlands.ragregress.gate.Threshold;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fluent assertions for retrieval quality, for use in JUnit or TestNG tests. Failures throw
 * {@link AssertionError} with the lowest-scoring queries in the message, so a red build says which
 * questions broke, not just that a number moved.
 *
 * <pre>{@code
 * RagAssertions.assertThat(report)
 *         .hasNoErrors()
 *         .metric("recall@5").isAtLeast(0.80)
 *         .metric("ndcg@10").isAtLeast(0.70);
 *
 * RagAssertions.assertThat(report)
 *         .comparedTo(EvalReport.load(Path.of("src/test/resources/retrieval-baseline.json")))
 *         .hasNoSignificantRegression();
 * }</pre>
 */
public final class RagAssertions {

    private static final int EXAMPLES = 5;

    private RagAssertions() {}

    public static ReportAssert assertThat(EvalReport report) {
        return new ReportAssert(report);
    }

    /** Assertions on a single evaluation. */
    public static final class ReportAssert {
        private final EvalReport report;

        private ReportAssert(EvalReport report) {
            this.report = report;
        }

        public MetricAssert metric(String name) {
            if (!report.hasMetric(name)) {
                // Fail through report.metric so the message lists what is available.
                report.metric(name);
            }
            return new MetricAssert(this, report, name);
        }

        /** Checks a threshold expression such as {@code "recall@5>=0.8"} or {@code "latency_p95<200ms"}. */
        public ReportAssert satisfies(String expression) {
            Threshold.ThresholdResult result = Threshold.parse(expression).evaluate(report);
            if (!result.passed()) {
                String message = expression + " failed: actual " + result.formattedActual() + ".";
                if (report.aggregate().containsKey(result.threshold().metric())) {
                    message += lowestScoring(report, result.threshold().metric());
                }
                throw new AssertionError(message);
            }
            return this;
        }

        public ReportAssert hasNoErrors() {
            List<QueryResult> failed = report.queries().stream().filter(QueryResult::failed).toList();
            if (!failed.isEmpty()) {
                StringBuilder message = new StringBuilder()
                        .append(failed.size()).append(" of ").append(report.size()).append(" queries failed to retrieve:");
                failed.stream().limit(EXAMPLES).forEach(q ->
                        message.append("\n  ").append(q.id()).append(": ").append(q.error()));
                throw new AssertionError(message.toString());
            }
            return this;
        }

        /** Compares against a baseline report with the default tolerance and bootstrap settings. */
        public ComparisonAssert comparedTo(EvalReport baseline) {
            return comparedTo(baseline, Comparison.Options.defaults());
        }

        public ComparisonAssert comparedTo(EvalReport baseline, Comparison.Options options) {
            return new ComparisonAssert(baseline, report, options);
        }
    }

    /** Assertions on one aggregate metric. */
    public static final class MetricAssert {
        private final ReportAssert parent;
        private final EvalReport report;
        private final String metric;

        private MetricAssert(ReportAssert parent, EvalReport report, String metric) {
            this.parent = parent;
            this.report = report;
            this.metric = metric;
        }

        public ReportAssert isAtLeast(double minimum) {
            double actual = report.metric(metric);
            if (!(actual >= minimum)) {
                throw new AssertionError(String.format(Locale.ROOT, "%s was %.3f, expected at least %.3f (%d queries).%s",
                        metric, actual, minimum, report.size(), lowestScoring(report, metric)));
            }
            return parent;
        }

        public ReportAssert isAtMost(double maximum) {
            double actual = report.metric(metric);
            if (!(actual <= maximum)) {
                throw new AssertionError(String.format(Locale.ROOT, "%s was %.3f, expected at most %.3f (%d queries).",
                        metric, actual, maximum, report.size()));
            }
            return parent;
        }
    }

    /** Assertions on a baseline comparison. */
    public static final class ComparisonAssert {
        private final EvalReport baseline;
        private final EvalReport current;
        private final Comparison.Options options;

        private ComparisonAssert(EvalReport baseline, EvalReport current, Comparison.Options options) {
            this.baseline = baseline;
            this.current = current;
            this.options = options;
        }

        /** No metric dropped beyond the tolerance with a significant bootstrap interval. */
        public ComparisonAssert hasNoSignificantRegression() {
            return check(Comparison.compare(baseline, current, options));
        }

        /** Like {@link #hasNoSignificantRegression()} but only for the named metrics. */
        public ComparisonAssert hasNoSignificantRegression(String... metrics) {
            return check(Comparison.compare(baseline, current, options.withGate(List.of(metrics))));
        }

        public Comparison comparison() {
            return Comparison.compare(baseline, current, options);
        }

        private ComparisonAssert check(Comparison comparison) {
            List<MetricDelta> regressions = comparison.regressions();
            if (regressions.isEmpty()) {
                return this;
            }
            StringBuilder message = new StringBuilder("retrieval quality regressed against the baseline ("
                    + comparison.matchedQueries() + " matched queries):");
            for (MetricDelta d : regressions) {
                message.append(String.format(Locale.ROOT, "%n  %s %.3f -> %.3f (%+.3f, %d%% CI [%+.3f, %+.3f])",
                        d.metric(), d.baseline(), d.current(), d.delta(),
                        Math.round(options.confidence() * 100), d.ciLow(), d.ciHigh()));
            }
            if (!comparison.largestDrops().isEmpty()) {
                message.append("\nLargest drops in ").append(comparison.primaryMetric()).append(':');
                for (QueryChange change : comparison.largestDrops()) {
                    message.append(String.format(Locale.ROOT, "%n  %s %.3f -> %.3f  \"%s\"", change.id(),
                            change.baseline(), change.current(), change.query()));
                    if (!change.lostRelevant().isEmpty()) {
                        message.append("  lost: ").append(String.join(", ", change.lostRelevant()));
                    }
                }
            }
            throw new AssertionError(message.toString());
        }
    }

    private static String lowestScoring(EvalReport report, String metric) {
        StringBuilder text = new StringBuilder("\nLowest-scoring queries:");
        report.queries().stream()
                .sorted(Comparator.comparingDouble((QueryResult q) -> q.metric(metric)).thenComparing(QueryResult::id))
                .limit(EXAMPLES)
                .forEach(q -> text.append(String.format(Locale.ROOT, "%n  %s %.3f  \"%s\"%s", q.id(), q.metric(metric),
                        q.query(), q.failed() ? "  (error: " + q.error() + ")" : "")));
        return text.toString();
    }
}
