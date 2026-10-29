package io.github.nikhilucumberlands.ragregress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagEvalTest {

    @Test
    void scoresEveryQueryAndAggregates() {
        EvalReport report = RagEval.builder(Fixtures.missing(0, 1)).ks(5, 1).name("fixture").build().evaluate(Fixtures.golden(10));

        assertEquals(List.of(1, 5), report.ks(), "ks are sorted");
        assertEquals(10, report.size());
        assertEquals(0.8, report.metric("recall@5"), 1e-12);
        assertEquals(0.8, report.metric("mrr@1"), 1e-12);
        assertEquals(0.0, report.metric("error_rate"));
        assertEquals("fixture", report.retriever());
        assertEquals(5, report.byTag().get("even").queries());
        assertEquals(0.8, report.byTag().get("even").metrics().get("recall@5"), 1e-12, "q0 misses among evens");
        assertEquals(0.8, report.byTag().get("odd").metrics().get("recall@5"), 1e-12, "q1 misses among odds");
        assertEquals(10, report.latency().measured());
    }

    @Test
    void failedRetrievalCountsAsZeroAndAsAnError() {
        Retriever flaky = (query, k) -> {
            if (query.endsWith("3")) {
                throw new IllegalStateException("index unavailable");
            }
            return List.of("doc-" + query.substring("question ".length()));
        };
        EvalReport report = RagEval.builder(flaky).ks(1).build().evaluate(Fixtures.golden(4));
        assertEquals(1, report.errors());
        assertEquals(0.25, report.errorRate(), 1e-12);
        assertEquals(0.75, report.metric("hit_rate@1"), 1e-12);
        QueryResult failed = report.queries().get(3);
        assertTrue(failed.failed());
        assertEquals("IllegalStateException: index unavailable", failed.error());
    }

    @Test
    void duplicatesAndBlanksAreDroppedBeforeScoring() {
        Retriever messy = (query, k) -> {
            List<String> ids = new ArrayList<>();
            ids.add("filler");
            ids.add("filler");
            ids.add("");
            ids.add(null);
            ids.add("doc-" + query.substring("question ".length()));
            return ids;
        };
        EvalReport report = RagEval.builder(messy).ks(2).build().evaluate(Fixtures.golden(1));
        assertEquals(List.of("filler", "doc-0"), report.queries().get(0).retrieved());
        assertEquals(0.5, report.metric("mrr@2"), 1e-12);
    }

    @Test
    void respectsTheConcurrencyLimit() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Retriever slow = (query, k) -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(20);
            inFlight.decrementAndGet();
            return List.of();
        };
        RagEval.builder(slow).concurrency(3).build().evaluate(Fixtures.golden(12));
        assertTrue(peak.get() <= 3, "peak " + peak.get());
        assertTrue(peak.get() >= 2, "should run queries in parallel, peak " + peak.get());
    }

    @Test
    void reportsSurviveAJsonRoundTrip(@TempDir Path dir) throws IOException {
        EvalReport report = RagEval.builder(Fixtures.missing(2)).ks(1, 3).build().evaluate(Fixtures.golden(6));
        Path file = dir.resolve("nested/report.json");
        report.save(file);
        EvalReport loaded = EvalReport.load(file);

        assertEquals(report.ks(), loaded.ks());
        assertEquals(report.size(), loaded.size());
        for (String metric : report.metricNames()) {
            assertEquals(report.metric(metric), loaded.metric(metric), 1e-6, metric);
        }
        assertEquals(report.queries().get(2).retrieved(), loaded.queries().get(2).retrieved());
        assertEquals(report.queries().get(0).tags(), loaded.queries().get(0).tags());
        assertEquals(report.createdAt(), loaded.createdAt());
    }

    @Test
    void unknownMetricsListWhatIsAvailable() {
        EvalReport report = RagEval.builder(Fixtures.perfect()).ks(5).build().evaluate(Fixtures.golden(2));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> report.metric("recall@10"));
        assertTrue(e.getMessage().contains("recall@5"), e.getMessage());
        assertFalse(report.hasMetric("recall@10"));
        assertTrue(report.hasMetric("latency_p95"));
    }

    @Test
    void builderValidatesInput() {
        assertThrows(IllegalArgumentException.class, () -> RagEval.builder(Fixtures.perfect()).ks(0));
        assertThrows(IllegalArgumentException.class, () -> RagEval.builder(Fixtures.perfect()).ks());
        assertThrows(IllegalArgumentException.class, () -> RagEval.builder(Fixtures.perfect()).concurrency(0));
    }
}
