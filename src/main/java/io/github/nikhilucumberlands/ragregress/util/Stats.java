package io.github.nikhilucumberlands.ragregress.util;

import java.util.Arrays;

/** Percentile and mean helpers. */
public final class Stats {

    private Stats() {}

    /**
     * Percentile with linear interpolation between closest ranks, the same method as NumPy's
     * default.
     *
     * @param sorted ascending values
     * @param p      percentile between 0 and 100
     */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        if (p <= 0) {
            return sorted[0];
        }
        if (p >= 100) {
            return sorted[sorted.length - 1];
        }
        double rank = (sorted.length - 1) * p / 100.0;
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower);
    }

    public static double mean(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    public static double[] sortedFinite(double[] values) {
        return Arrays.stream(values).filter(Double::isFinite).sorted().toArray();
    }
}
