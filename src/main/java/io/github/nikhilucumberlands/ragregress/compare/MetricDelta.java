package io.github.nikhilucumberlands.ragregress.compare;

/**
 * How one metric moved between baseline and current, over the queries both runs share.
 *
 * @param ciLow  lower bound of the bootstrap interval for {@code delta}; NaN when disabled
 * @param ciHigh upper bound of the bootstrap interval for {@code delta}; NaN when disabled
 * @param gated  whether a regression in this metric fails the check
 */
public record MetricDelta(String metric, double baseline, double current, double delta,
                          double ciLow, double ciHigh, Status status, boolean gated) {

    public enum Status { REGRESSED, IMPROVED, UNCHANGED }

    public boolean failsGate() {
        return gated && status == Status.REGRESSED;
    }
}
