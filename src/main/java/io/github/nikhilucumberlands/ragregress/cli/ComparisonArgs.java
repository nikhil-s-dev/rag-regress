package io.github.nikhilucumberlands.ragregress.cli;

import io.github.nikhilucumberlands.ragregress.compare.Comparison;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Options shared by {@code run --baseline} and {@code compare}. */
final class ComparisonArgs {

    static final Set<String> VALUE_OPTIONS = Set.of("gate", "tolerance", "confidence", "resamples", "seed", "primary", "top-drops");
    static final Set<String> SWITCHES = Set.of("no-bootstrap");

    static final String HELP = """
              --gate METRICS             comma-separated metrics that fail the check when they regress
                                         (default: all ranking metrics)
              --tolerance DELTA          drop that is always accepted, as a fraction (default: 0.01)
              --confidence LEVEL         bootstrap interval level (default: 0.95)
              --resamples N              bootstrap resamples (default: 2000)
              --no-bootstrap             flag any drop beyond the tolerance, without a significance test
              --primary METRIC           metric used to list the worst queries (default: ndcg at largest k)
              --top-drops N              how many of the worst queries to list (default: 5)
              --seed N                   bootstrap seed (default: 0)
            """;

    private ComparisonArgs() {}

    static Comparison.Options parse(Args args) throws UsageException {
        List<String> gate = args.has("gate") ? splitList(args.value("gate")) : List.of();
        int resamples = args.flag("no-bootstrap") ? 0 : args.intValue("resamples", 2000, 1);
        try {
            return new Comparison.Options(
                    args.doubleInRange("tolerance", 0.01, 0, 1),
                    args.doubleInRange("confidence", 0.95, 0.5, 0.999),
                    resamples,
                    args.longValue("seed", 0),
                    gate,
                    args.value("primary") == null ? null : args.value("primary").strip().toLowerCase(java.util.Locale.ROOT),
                    args.intValue("top-drops", 5, 0));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    static List<String> splitList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }
}
