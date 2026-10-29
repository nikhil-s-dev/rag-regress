package io.github.nikhilucumberlands.ragregress.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.GoldenQuery;
import io.github.nikhilucumberlands.ragregress.GoldenSet;
import io.github.nikhilucumberlands.ragregress.RagEval;
import io.github.nikhilucumberlands.ragregress.Retriever;
import io.github.nikhilucumberlands.ragregress.compare.MetricDelta.Status;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComparisonTest {

    /** 40 queries, each with one relevant document "doc-i". */
    private static GoldenSet golden() {
        List<GoldenQuery> queries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            queries.add(new GoldenQuery("q" + i, "question " + i, Map.of("doc-" + i, 1.0), List.of()));
        }
        return GoldenSet.of("test", queries);
    }

    /** Finds the right document for every query except those listed, which get filler only. */
    private static EvalReport evaluate(Set<Integer> misses) {
        Retriever retriever = (query, k) -> {
            int i = Integer.parseInt(query.substring("question ".length()));
            return misses.contains(i) ? List.of("filler-a", "filler-b") : List.of("filler-a", "doc-" + i);
        };
        return RagEval.builder(retriever).ks(1, 5).build().evaluate(golden());
    }

    private static MetricDelta delta(Comparison comparison, String metric) {
        return comparison.metrics().stream().filter(d -> d.metric().equals(metric)).findFirst().orElseThrow();
    }

    @Test
    void identicalRunsDoNotRegress() {
        EvalReport report = evaluate(Set.of(3, 7));
        Comparison comparison = Comparison.compare(report, report, Comparison.Options.defaults());
        assertFalse(comparison.hasRegression());
        assertEquals(40, comparison.matchedQueries());
        comparison.metrics().forEach(d -> {
            assertEquals(Status.UNCHANGED, d.status(), d.metric());
            assertEquals(0.0, d.ciLow(), 1e-12);
            assertEquals(0.0, d.ciHigh(), 1e-12);
        });
        assertTrue(comparison.largestDrops().isEmpty());
    }

    @Test
    void aBaselineLoadedFromJsonMatchesTheSameRunExactly(@TempDir Path dir) throws IOException {
        // The relevant document sits at rank 2, so nDCG is 1/log2(3) = 0.6309..., which JSON rounds.
        EvalReport report = evaluate(Set.of(3));
        Path file = dir.resolve("baseline.json");
        report.save(file);
        Comparison comparison = Comparison.compare(EvalReport.load(file), report, Comparison.Options.defaults());
        comparison.metrics().forEach(d -> assertEquals(0.0, d.delta(), d.metric()));
        assertTrue(comparison.largestDrops().isEmpty());
    }

    @Test
    void aBroadDropIsFlaggedAndExplained() {
        EvalReport baseline = evaluate(Set.of());
        EvalReport current = evaluate(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));
        Comparison comparison = Comparison.compare(baseline, current, Comparison.Options.defaults());

        MetricDelta recall = delta(comparison, "recall@5");
        assertEquals(1.0, recall.baseline(), 1e-12);
        assertEquals(0.7, recall.current(), 1e-12);
        assertEquals(Status.REGRESSED, recall.status());
        assertTrue(recall.ciLow() <= -0.3 && recall.ciHigh() >= -0.3 && recall.ciHigh() < 0,
                "interval [" + recall.ciLow() + ", " + recall.ciHigh() + "] should bracket -0.3 and exclude 0");
        assertTrue(comparison.hasRegression());
        assertEquals("ndcg@5", comparison.primaryMetric());
        assertEquals(5, comparison.largestDrops().size());
        QueryChange first = comparison.largestDrops().get(0);
        assertEquals("q0", first.id());
        assertEquals(List.of("doc-0"), first.lostRelevant());
    }

    @Test
    void aOneQueryWobbleIsNotSignificant() {
        EvalReport baseline = evaluate(Set.of(5));
        EvalReport current = evaluate(Set.of(6, 7));   // net change: one more miss out of 40
        Comparison withBootstrap = Comparison.compare(baseline, current, Comparison.Options.defaults());
        MetricDelta recall = delta(withBootstrap, "recall@5");
        assertEquals(-0.025, recall.delta(), 1e-12);
        assertTrue(recall.ciLow() < 0 && recall.ciHigh() > 0, "interval should straddle zero");
        assertEquals(Status.UNCHANGED, recall.status());
        assertFalse(withBootstrap.hasRegression());

        Comparison toleranceOnly = Comparison.compare(baseline, current, Comparison.Options.defaults().withResamples(0));
        assertEquals(Status.REGRESSED, delta(toleranceOnly, "recall@5").status());
        assertTrue(Double.isNaN(delta(toleranceOnly, "recall@5").ciLow()));
    }

    @Test
    void onlyGatedMetricsFailTheComparison() {
        EvalReport baseline = evaluate(Set.of());
        EvalReport current = evaluate(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));
        Comparison comparison = Comparison.compare(baseline, current,
                Comparison.Options.defaults().withGate(List.of("precision@1")));
        // precision@1 is 0 in both runs because the right document is always second.
        assertFalse(comparison.hasRegression());
        assertEquals(Status.REGRESSED, delta(comparison, "recall@5").status());
        assertFalse(delta(comparison, "recall@5").gated());
    }

    @Test
    void improvementsAreReportedButNeverFail() {
        Comparison comparison = Comparison.compare(evaluate(Set.of(1, 2, 3, 4, 5, 6, 7, 8)), evaluate(Set.of()),
                Comparison.Options.defaults());
        assertEquals(Status.IMPROVED, delta(comparison, "recall@5").status());
        assertFalse(comparison.hasRegression());
    }

    @Test
    void comparesOnlySharedQueries() {
        EvalReport baseline = evaluate(Set.of());
        List<GoldenQuery> fewer = golden().queries().subList(0, 30);
        Retriever same = (query, k) -> List.of("filler-a", "doc-" + query.substring("question ".length()));
        EvalReport current = RagEval.builder(same).ks(1, 5).build().evaluate(GoldenSet.of("fewer", fewer));
        Comparison comparison = Comparison.compare(baseline, current, Comparison.Options.defaults());
        assertEquals(30, comparison.matchedQueries());
        assertEquals(10, comparison.onlyInBaseline().size());
        assertFalse(comparison.hasRegression());
    }

    @Test
    void rejectsIncompatibleReports() {
        EvalReport report = evaluate(Set.of());
        assertThrows(IllegalArgumentException.class, () -> Comparison.compare(report, report,
                Comparison.Options.defaults().withGate(List.of("recall@10"))));
        Retriever any = (query, k) -> List.of();
        EvalReport otherKs = RagEval.builder(any).ks(3).build().evaluate(golden());
        assertThrows(IllegalArgumentException.class, () -> Comparison.compare(report, otherKs, Comparison.Options.defaults()));
    }

    @Test
    void bootstrapIsDeterministicAndBracketsTheMean() {
        double[][] deltas = {{-1, 0, 0, 0, -1, 0, 0, 0, 0, 0}};
        double[][] a = Bootstrap.pairedMeanIntervals(deltas, 3000, 0.95, 42);
        double[][] b = Bootstrap.pairedMeanIntervals(deltas, 3000, 0.95, 42);
        assertEquals(a[0][0], b[0][0]);
        assertEquals(a[0][1], b[0][1]);
        assertTrue(a[0][0] < -0.2 && a[0][0] >= -0.6, "lower " + a[0][0]);
        assertTrue(a[0][1] <= 0 && a[0][1] > -0.2, "upper " + a[0][1]);
    }
}
