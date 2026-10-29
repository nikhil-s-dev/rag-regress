package io.github.nikhilucumberlands.ragregress.report;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.EvalReport.TagSummary;
import io.github.nikhilucumberlands.ragregress.compare.Comparison;
import io.github.nikhilucumberlands.ragregress.compare.MetricDelta;
import io.github.nikhilucumberlands.ragregress.compare.QueryChange;
import io.github.nikhilucumberlands.ragregress.gate.Threshold.ThresholdResult;
import io.github.nikhilucumberlands.ragregress.metrics.RankingMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders an {@link Outcome} for terminals, GitHub job summaries (Markdown) and pipelines (JSON). */
public final class Reports {

    private Reports() {}

    // ---- console ------------------------------------------------------------------------------

    public static String console(Outcome outcome) {
        EvalReport r = outcome.report();
        StringBuilder out = new StringBuilder();
        out.append("rag-regress ").append(r.version()).append('\n');
        line(out, "golden set", r.dataset() + " (" + r.size() + " queries)");
        line(out, "retriever", r.retriever());
        out.append('\n');
        metricTable(out, r);
        out.append('\n');
        line(out, "latency", latencyText(r));
        line(out, "errors", r.errors() + " of " + r.size() + " queries");
        String primary = primaryMetric(outcome);
        if (!r.byTag().isEmpty()) {
            out.append("\nby tag (").append(primary).append(")\n");
            r.byTag().forEach((tag, s) -> out.append(String.format(Locale.ROOT, "  %-14s %.3f  (%d queries)%n",
                    tag, tagMetric(s, primary), s.queries())));
        }
        if (!outcome.thresholds().isEmpty()) {
            out.append("\nthresholds\n");
            for (ThresholdResult t : outcome.thresholds()) {
                out.append(String.format(Locale.ROOT, "  %s  %-24s %s%n",
                        t.passed() ? "PASS" : "FAIL", t.threshold().expression(), t.formattedActual()));
            }
        }
        Comparison c = outcome.comparison();
        if (c != null) {
            comparisonConsole(out, c);
        }
        out.append('\n');
        line(out, "result", outcome.passed() ? "PASS" : "FAIL: " + String.join("; ", outcome.failures()));
        return out.toString();
    }

    /** Console view of a comparison on its own, used by the compare command. */
    public static String comparisonOnly(Comparison comparison, EvalReport baseline, EvalReport current) {
        StringBuilder out = new StringBuilder();
        line(out, "baseline", baseline.retriever() + " (" + baseline.createdAt() + ")");
        line(out, "current", current.retriever() + " (" + current.createdAt() + ")");
        comparisonConsole(out, comparison);
        out.append('\n');
        line(out, "result", comparison.hasRegression()
                ? "FAIL: " + Outcome.describeRegressions(comparison.regressions())
                : "PASS");
        return out.toString();
    }

    private static void comparisonConsole(StringBuilder out, Comparison c) {
        out.append('\n').append(comparisonHeading(c)).append('\n');
        out.append(String.format(Locale.ROOT, "  %-14s %9s %9s %9s   %-20s %s%n",
                "metric", "baseline", "current", "delta", ciLabel(c), "status"));
        for (MetricDelta d : gated(c)) {
            out.append(String.format(Locale.ROOT, "  %-14s %9.3f %9.3f %+9.3f   %-20s %s%n",
                    d.metric(), d.baseline(), d.current(), d.delta(), interval(d), status(d)));
        }
        String others = ungatedSummary(c);
        if (others != null) {
            out.append("  ").append(others).append('\n');
        }
        if (!c.largestDrops().isEmpty()) {
            out.append("\nlargest drops in ").append(c.primaryMetric()).append('\n');
            for (QueryChange q : c.largestDrops()) {
                out.append(String.format(Locale.ROOT, "  %-8s %.3f -> %.3f  %s%n", q.id(), q.baseline(), q.current(), q.query()));
                if (!q.lostRelevant().isEmpty()) {
                    out.append("           lost from top ").append(k(c.primaryMetric())).append(": ")
                            .append(String.join(", ", q.lostRelevant())).append('\n');
                }
            }
        }
        if (!c.onlyInBaseline().isEmpty() || !c.onlyInCurrent().isEmpty()) {
            out.append(String.format(Locale.ROOT, "%nnote: %d queries only in the baseline, %d only in the current run; "
                    + "they are left out of the comparison%n", c.onlyInBaseline().size(), c.onlyInCurrent().size()));
        }
    }

    private static void metricTable(StringBuilder out, EvalReport r) {
        out.append(String.format(Locale.ROOT, "%-12s", "metric"));
        for (int k : r.ks()) {
            out.append(String.format(Locale.ROOT, "%8s", "@" + k));
        }
        out.append('\n');
        for (String family : RankingMetrics.FAMILIES) {
            out.append(String.format(Locale.ROOT, "%-12s", family));
            for (int k : r.ks()) {
                out.append(String.format(Locale.ROOT, "%8.3f", r.aggregate().get(RankingMetrics.name(family, k))));
            }
            out.append('\n');
        }
    }

    // ---- markdown -----------------------------------------------------------------------------

    public static String markdown(Outcome outcome) {
        EvalReport r = outcome.report();
        StringBuilder md = new StringBuilder();
        md.append("### rag-regress: ").append(outcome.passed() ? "PASS" : "FAIL").append("\n\n");
        md.append("**Golden set:** `").append(r.dataset()).append("` (").append(r.size()).append(" queries)  \n");
        md.append("**Retriever:** `").append(r.retriever()).append("`  \n");
        md.append("**Latency:** ").append(latencyText(r)).append(" · **errors:** ").append(r.errors()).append("\n\n");

        md.append("| metric |");
        r.ks().forEach(k -> md.append(" @").append(k).append(" |"));
        md.append("\n|:--|");
        r.ks().forEach(k -> md.append("--:|"));
        md.append('\n');
        for (String family : RankingMetrics.FAMILIES) {
            md.append("| ").append(family).append(" |");
            r.ks().forEach(k -> md.append(String.format(Locale.ROOT, " %.3f |", r.aggregate().get(RankingMetrics.name(family, k)))));
            md.append('\n');
        }
        if (!outcome.thresholds().isEmpty()) {
            md.append("\n| threshold | actual | result |\n|:--|--:|:--|\n");
            for (ThresholdResult t : outcome.thresholds()) {
                md.append("| `").append(t.threshold().expression()).append("` | ").append(t.formattedActual())
                        .append(" | ").append(t.passed() ? "✅ pass" : "❌ fail").append(" |\n");
            }
        }
        Comparison c = outcome.comparison();
        if (c != null) {
            md.append("\n**").append(comparisonHeading(c)).append("**\n\n");
            md.append("| metric | baseline | current | delta | ").append(ciLabel(c)).append(" | status |\n");
            md.append("|:--|--:|--:|--:|:--|:--|\n");
            for (MetricDelta d : gated(c)) {
                md.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %+.3f | %s | %s |%n", d.metric(), d.baseline(),
                        d.current(), d.delta(), interval(d), d.failsGate() ? "❌ regressed" : status(d)));
            }
            String others = ungatedSummary(c);
            if (others != null) {
                md.append('\n').append(others.substring(0, 1).toUpperCase(Locale.ROOT)).append(others.substring(1)).append('\n');
            }
            if (!c.largestDrops().isEmpty()) {
                md.append("\n| query | ").append(c.primaryMetric()).append(" | lost relevant docs |\n|:--|:--|:--|\n");
                for (QueryChange q : c.largestDrops()) {
                    md.append(String.format(Locale.ROOT, "| `%s` %s | %.3f → %.3f | %s |%n", q.id(), escape(q.query()),
                            q.baseline(), q.current(), q.lostRelevant().isEmpty() ? "" : "`" + String.join("`, `", q.lostRelevant()) + "`"));
                }
            }
        }
        if (!outcome.passed()) {
            md.append("\n**Failed:** ").append(String.join("; ", outcome.failures())).append('\n');
        }
        return md.toString();
    }

    // ---- json ---------------------------------------------------------------------------------

    /** The report itself (usable as a future baseline) plus a {@code check} section. */
    public static Map<String, Object> json(Outcome outcome) {
        Map<String, Object> root = outcome.report().toJson();
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("passed", outcome.passed());
        check.put("failures", outcome.failures());
        List<Object> thresholds = new ArrayList<>();
        for (ThresholdResult t : outcome.thresholds()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("expression", t.threshold().expression());
            entry.put("actual", round(t.actual()));
            entry.put("passed", t.passed());
            thresholds.add(entry);
        }
        check.put("thresholds", thresholds);
        Comparison c = outcome.comparison();
        if (c != null) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("matched_queries", c.matchedQueries());
            comparison.put("only_in_baseline", c.onlyInBaseline());
            comparison.put("only_in_current", c.onlyInCurrent());
            comparison.put("tolerance", c.options().tolerance());
            comparison.put("confidence", c.options().confidence());
            comparison.put("resamples", c.options().resamples());
            List<Object> metrics = new ArrayList<>();
            for (MetricDelta d : c.metrics()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("metric", d.metric());
                entry.put("baseline", round(d.baseline()));
                entry.put("current", round(d.current()));
                entry.put("delta", round(d.delta()));
                entry.put("ci_low", round(d.ciLow()));
                entry.put("ci_high", round(d.ciHigh()));
                entry.put("status", d.status().name().toLowerCase(Locale.ROOT));
                entry.put("gated", d.gated());
                metrics.add(entry);
            }
            comparison.put("metrics", metrics);
            List<Object> drops = new ArrayList<>();
            for (QueryChange q : c.largestDrops()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", q.id());
                entry.put("query", q.query());
                entry.put("metric", c.primaryMetric());
                entry.put("baseline", round(q.baseline()));
                entry.put("current", round(q.current()));
                entry.put("lost_relevant", q.lostRelevant());
                entry.put("gained_relevant", q.gainedRelevant());
                drops.add(entry);
            }
            comparison.put("largest_drops", drops);
            check.put("comparison", comparison);
        }
        root.put("check", check);
        return root;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String primaryMetric(Outcome outcome) {
        if (outcome.comparison() != null) {
            return outcome.comparison().primaryMetric();
        }
        List<Integer> ks = outcome.report().ks();
        return RankingMetrics.name("ndcg", ks.get(ks.size() - 1));
    }

    private static String comparisonHeading(Comparison c) {
        String method = c.options().resamples() > 0
                ? String.format(Locale.ROOT, "%d%% bootstrap CI, %d resamples", Math.round(c.options().confidence() * 100), c.options().resamples())
                : "no significance test";
        return String.format(Locale.ROOT, "compared with baseline (%d matched queries; tolerance %.3f; %s)",
                c.matchedQueries(), c.options().tolerance(), method);
    }

    private static String ciLabel(Comparison c) {
        return c.options().resamples() > 0 ? Math.round(c.options().confidence() * 100) + "% CI" : "CI";
    }

    private static List<MetricDelta> gated(Comparison c) {
        return c.metrics().stream().filter(MetricDelta::gated).toList();
    }

    /** One line about metrics outside the gate, or null when every metric is gated. */
    private static String ungatedSummary(Comparison c) {
        List<MetricDelta> others = c.metrics().stream().filter(d -> !d.gated()).toList();
        if (others.isEmpty()) {
            return null;
        }
        long regressed = others.stream().filter(d -> d.status() == MetricDelta.Status.REGRESSED).count();
        long improved = others.stream().filter(d -> d.status() == MetricDelta.Status.IMPROVED).count();
        return String.format(Locale.ROOT, "%d other metrics not gated: %d regressed, %d improved, %d unchanged (all in the JSON report)",
                others.size(), regressed, improved, others.size() - regressed - improved);
    }

    private static String status(MetricDelta d) {
        return switch (d.status()) {
            case REGRESSED -> d.gated() ? "REGRESSED" : "regressed (not gated)";
            case IMPROVED -> "improved";
            case UNCHANGED -> "no significant change";
        };
    }

    private static String interval(MetricDelta d) {
        if (Double.isNaN(d.ciLow())) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "[%+.3f, %+.3f]", d.ciLow(), d.ciHigh());
    }

    private static String latencyText(EvalReport r) {
        EvalReport.LatencySummary l = r.latency();
        if (l.measured() == 0) {
            return "not measured";
        }
        return String.format(Locale.ROOT, "p50 %.1f ms, p95 %.1f ms, max %.1f ms", l.p50() * 1000, l.p95() * 1000, l.max() * 1000);
    }

    private static int k(String metric) {
        return Integer.parseInt(metric.substring(metric.indexOf('@') + 1));
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(String.format(Locale.ROOT, "%-12s%s%n", label, value));
    }

    private static Double round(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static double tagMetric(TagSummary summary, String metric) {
        return summary.metrics().getOrDefault(metric, Double.NaN);
    }
}
