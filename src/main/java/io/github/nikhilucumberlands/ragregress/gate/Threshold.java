package io.github.nikhilucumberlands.ragregress.gate;

import io.github.nikhilucumberlands.ragregress.EvalReport;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An absolute floor or ceiling on a metric, written as {@code <metric><op><value>[unit]}:
 *
 * <pre>
 *   recall@5&gt;=0.8     ndcg@10&gt;=75%     error_rate&lt;=1%     latency_p95&lt;300ms
 * </pre>
 *
 * Ranking metrics and {@code error_rate} take a fraction or a percentage. Latency metrics
 * ({@code latency_p50}, {@code latency_p95}, {@code latency_max}) need {@code ms} or {@code s}.
 *
 * @param value threshold in base units: a 0..1 fraction or seconds
 */
public record Threshold(String expression, String metric, Operator operator, double value) {

    private static final Pattern SYNTAX = Pattern.compile(
            "^\\s*([a-z][a-z0-9_]*(?:@[0-9]+)?)\\s*(<=|>=|<|>)\\s*([0-9]*\\.?[0-9]+)\\s*(ms|s|%)?\\s*$");

    public enum Operator {
        LT("<"), LE("<="), GT(">"), GE(">=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean test(double actual, double limit) {
            return switch (this) {
                case LT -> actual < limit;
                case LE -> actual <= limit;
                case GT -> actual > limit;
                case GE -> actual >= limit;
            };
        }

        static Operator of(String symbol) {
            for (Operator operator : values()) {
                if (operator.symbol.equals(symbol)) {
                    return operator;
                }
            }
            throw new IllegalArgumentException("unknown operator " + symbol);
        }
    }

    public static Threshold parse(String expression) {
        Matcher m = SYNTAX.matcher(expression.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("threshold '" + expression
                    + "' must look like recall@5>=0.8, error_rate<=1% or latency_p95<300ms");
        }
        String metric = m.group(1);
        double number = Double.parseDouble(m.group(3));
        String unit = m.group(4);
        double value;
        if (isLatency(metric)) {
            if (!"ms".equals(unit) && !"s".equals(unit)) {
                throw new IllegalArgumentException(metric + " is a latency; give the limit in ms or s, e.g. " + metric + "<300ms");
            }
            value = unit.equals("ms") ? number / 1000 : number;
        } else {
            if ("ms".equals(unit) || "s".equals(unit)) {
                throw new IllegalArgumentException(metric + " is a score; use a fraction (0.8) or a percentage (80%)");
            }
            value = "%".equals(unit) ? number / 100 : number;
            if (value > 1) {
                throw new IllegalArgumentException(metric + " ranges from 0 to 1; did you mean " + number + "%?");
            }
        }
        return new Threshold(expression.strip(), metric, Operator.of(m.group(2)), value);
    }

    public ThresholdResult evaluate(EvalReport report) {
        double actual = report.metric(metric);
        if (Double.isNaN(actual)) {
            return new ThresholdResult(this, actual, false);
        }
        return new ThresholdResult(this, actual, operator.test(actual, value));
    }

    public boolean latency() {
        return isLatency(metric);
    }

    static boolean isLatency(String metric) {
        return metric.startsWith("latency_");
    }

    /** Formats a value of this metric for people: latencies in ms, scores with three decimals. */
    public String format(double actual) {
        if (Double.isNaN(actual)) {
            return "n/a";
        }
        if (latency()) {
            return String.format(Locale.ROOT, "%.1f ms", actual * 1000);
        }
        return metric.equals("error_rate")
                ? String.format(Locale.ROOT, "%.2f%%", actual * 100)
                : String.format(Locale.ROOT, "%.3f", actual);
    }

    /** Outcome of checking one threshold. */
    public record ThresholdResult(Threshold threshold, double actual, boolean passed) {
        public String formattedActual() {
            return threshold.format(actual);
        }
    }
}
