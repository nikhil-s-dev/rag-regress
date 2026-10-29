package io.github.nikhilucumberlands.ragregress.compare;

import io.github.nikhilucumberlands.ragregress.util.Stats;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Paired bootstrap confidence intervals for the mean of per-query differences.
 *
 * <p>Golden sets are small, often 30 to 300 queries, so a drop of a few points in recall@5 can be
 * noise from a handful of queries. Resampling queries with replacement and recomputing the mean
 * difference shows how much the number would move with a different sample of questions. All
 * metrics share the same resampled queries, so their intervals are consistent with each other.
 */
public final class Bootstrap {

    private Bootstrap() {}

    /**
     * @param deltas     deltas[metric][query] = current - baseline for the same query
     * @param resamples  number of bootstrap samples, e.g. 2000
     * @param confidence e.g. 0.95 for a 95% interval
     * @return [metric][0] lower bound, [metric][1] upper bound
     */
    public static double[][] pairedMeanIntervals(double[][] deltas, int resamples, double confidence, long seed) {
        if (resamples < 1) {
            throw new IllegalArgumentException("resamples must be at least 1");
        }
        if (!(confidence > 0 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        int metrics = deltas.length;
        int n = deltas[0].length;
        double[][] means = new double[metrics][resamples];
        SplittableRandom random = new SplittableRandom(seed);
        int[] sample = new int[n];
        for (int b = 0; b < resamples; b++) {
            for (int i = 0; i < n; i++) {
                sample[i] = random.nextInt(n);
            }
            for (int m = 0; m < metrics; m++) {
                double[] d = deltas[m];
                double sum = 0;
                for (int index : sample) {
                    sum += d[index];
                }
                means[m][b] = sum / n;
            }
        }
        double tail = (1 - confidence) / 2 * 100;
        double[][] intervals = new double[metrics][2];
        for (int m = 0; m < metrics; m++) {
            Arrays.sort(means[m]);
            intervals[m][0] = Stats.percentile(means[m], tail);
            intervals[m][1] = Stats.percentile(means[m], 100 - tail);
        }
        return intervals;
    }
}
