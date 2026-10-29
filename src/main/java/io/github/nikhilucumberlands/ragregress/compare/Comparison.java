package io.github.nikhilucumberlands.ragregress.compare;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.QueryResult;
import io.github.nikhilucumberlands.ragregress.compare.MetricDelta.Status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baseline versus current, paired by query id.
 *
 * <p>A metric counts as <b>regressed</b> when its mean dropped by more than the tolerance
 * <em>and</em> the bootstrap interval of the drop lies entirely below zero. Requiring both keeps CI
 * from failing on noise from a few queries while still catching real drops on small golden sets.
 * With bootstrapping disabled ({@code resamples = 0}) only the tolerance applies.
 */
public record Comparison(
        String primaryMetric,
        int matchedQueries,
        List<String> onlyInBaseline,
        List<String> onlyInCurrent,
        List<MetricDelta> metrics,
        List<QueryChange> largestDrops,
        Options options) {

    /**
     * @param tolerance  absolute drop that is always acceptable, e.g. 0.01 for one point
     * @param confidence bootstrap interval level, e.g. 0.95
     * @param resamples  bootstrap samples; 0 disables the significance check
     * @param gate       metrics whose regression fails the comparison; empty means all
     * @param primary    metric used to rank per-query drops; null picks ndcg at the largest k
     * @param topDrops   how many of the worst queries to list
     */
    public record Options(double tolerance, double confidence, int resamples, long seed,
                          List<String> gate, String primary, int topDrops) {

        public Options {
            if (tolerance < 0) {
                throw new IllegalArgumentException("tolerance cannot be negative");
            }
            if (!(confidence > 0 && confidence < 1)) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            if (resamples < 0) {
                throw new IllegalArgumentException("resamples cannot be negative");
            }
            if (topDrops < 0) {
                throw new IllegalArgumentException("topDrops cannot be negative");
            }
            gate = List.copyOf(gate == null ? List.of() : gate);
        }

        public static Options defaults() {
            return new Options(0.01, 0.95, 2000, 0, List.of(), null, 5);
        }

        public Options withGate(List<String> metrics) {
            return new Options(tolerance, confidence, resamples, seed, metrics, primary, topDrops);
        }

        public Options withTolerance(double value) {
            return new Options(value, confidence, resamples, seed, gate, primary, topDrops);
        }

        public Options withResamples(int value) {
            return new Options(tolerance, confidence, value, seed, gate, primary, topDrops);
        }
    }

    public Comparison {
        onlyInBaseline = List.copyOf(onlyInBaseline);
        onlyInCurrent = List.copyOf(onlyInCurrent);
        metrics = List.copyOf(metrics);
        largestDrops = List.copyOf(largestDrops);
    }

    public static Comparison compare(EvalReport baseline, EvalReport current, Options options) {
        Map<String, QueryResult> baselineById = new LinkedHashMap<>();
        baseline.queries().forEach(q -> baselineById.put(q.id(), q));
        Set<String> currentIds = new HashSet<>();
        List<QueryResult[]> pairs = new ArrayList<>();
        List<String> onlyInCurrent = new ArrayList<>();
        for (QueryResult q : current.queries()) {
            currentIds.add(q.id());
            QueryResult before = baselineById.get(q.id());
            if (before == null) {
                onlyInCurrent.add(q.id());
            } else {
                pairs.add(new QueryResult[] {before, q});
            }
        }
        List<String> onlyInBaseline = baselineById.keySet().stream().filter(id -> !currentIds.contains(id)).toList();
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("baseline and current reports have no query ids in common");
        }

        List<String> common = current.metricNames().stream().filter(baseline.aggregate()::containsKey).toList();
        if (common.isEmpty()) {
            throw new IllegalArgumentException("baseline and current reports share no metrics; check the k values");
        }
        for (String metric : options.gate()) {
            if (!common.contains(metric)) {
                throw new IllegalArgumentException("gate metric '" + metric + "' is not in both reports; available: "
                        + String.join(", ", common));
            }
        }
        String primary = options.primary() != null ? options.primary() : defaultPrimary(common);
        if (!common.contains(primary)) {
            throw new IllegalArgumentException("primary metric '" + primary + "' is not in both reports");
        }

        int n = pairs.size();
        double[][] deltas = new double[common.size()][n];
        double[] baselineMeans = new double[common.size()];
        double[] currentMeans = new double[common.size()];
        for (int m = 0; m < common.size(); m++) {
            String metric = common.get(m);
            for (int i = 0; i < n; i++) {
                double before = rounded(pairs.get(i)[0].metric(metric));
                double after = rounded(pairs.get(i)[1].metric(metric));
                baselineMeans[m] += before / n;
                currentMeans[m] += after / n;
                deltas[m][i] = after - before;
            }
        }
        double[][] intervals = options.resamples() > 0
                ? Bootstrap.pairedMeanIntervals(deltas, options.resamples(), options.confidence(), options.seed())
                : null;

        List<MetricDelta> metricDeltas = new ArrayList<>();
        for (int m = 0; m < common.size(); m++) {
            double delta = currentMeans[m] - baselineMeans[m];
            double low = intervals == null ? Double.NaN : intervals[m][0];
            double high = intervals == null ? Double.NaN : intervals[m][1];
            Status status;
            if (intervals == null) {
                status = delta < -options.tolerance() ? Status.REGRESSED
                        : delta > options.tolerance() ? Status.IMPROVED : Status.UNCHANGED;
            } else {
                status = delta < -options.tolerance() && high < 0 ? Status.REGRESSED
                        : delta > options.tolerance() && low > 0 ? Status.IMPROVED : Status.UNCHANGED;
            }
            boolean gated = options.gate().isEmpty() || options.gate().contains(common.get(m));
            metricDeltas.add(new MetricDelta(common.get(m), baselineMeans[m], currentMeans[m], delta, low, high, status, gated));
        }

        int k = Integer.parseInt(primary.substring(primary.indexOf('@') + 1));
        List<QueryChange> drops = pairs.stream()
                .map(pair -> change(pair[0], pair[1], primary, k))
                .filter(change -> change.delta() < 0)
                .sorted(Comparator.comparingDouble(QueryChange::delta).thenComparing(QueryChange::id))
                .limit(options.topDrops())
                .toList();

        return new Comparison(primary, n, onlyInBaseline, onlyInCurrent, metricDeltas, drops, options);
    }

    private static String defaultPrimary(List<String> common) {
        String best = null;
        int bestK = -1;
        for (String metric : common) {
            if (metric.startsWith("ndcg@")) {
                int k = Integer.parseInt(metric.substring(5));
                if (k > bestK) {
                    bestK = k;
                    best = metric;
                }
            }
        }
        return best != null ? best : common.get(0);
    }

    private static QueryChange change(QueryResult before, QueryResult after, String metric, int k) {
        Set<String> beforeTop = new HashSet<>(before.retrieved().subList(0, Math.min(k, before.retrieved().size())));
        Set<String> afterTop = new HashSet<>(after.retrieved().subList(0, Math.min(k, after.retrieved().size())));
        List<String> lost = new ArrayList<>();
        List<String> gained = new ArrayList<>();
        for (Map.Entry<String, Double> entry : after.relevance().entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            String doc = entry.getKey();
            if (beforeTop.contains(doc) && !afterTop.contains(doc)) {
                lost.add(doc);
            } else if (!beforeTop.contains(doc) && afterTop.contains(doc)) {
                gained.add(doc);
            }
        }
        return new QueryChange(after.id(), after.query(), rounded(before.metric(metric)), rounded(after.metric(metric)),
                lost, gained);
    }

    /**
     * Per-query scores are compared at the precision reports are saved with (six decimals), so a
     * baseline loaded from JSON and the same run held in memory compare as exactly equal.
     */
    private static double rounded(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    /** Gated metrics that regressed. */
    public List<MetricDelta> regressions() {
        return metrics.stream().filter(MetricDelta::failsGate).toList();
    }

    public boolean hasRegression() {
        return metrics.stream().anyMatch(MetricDelta::failsGate);
    }
}
