package io.github.nikhilucumberlands.ragregress.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RankingMetricsTest {

    private static final double EPS = 1e-9;

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    @Test
    void binaryRelevanceMatchesHandComputedValues() {
        // Relevant: a, b, c. Retrieved: x a y b z  -> hits at ranks 2 and 4.
        Map<String, Double> relevance = Map.of("a", 1.0, "b", 1.0, "c", 1.0);
        Map<String, Double> m = RankingMetrics.compute(List.of("x", "a", "y", "b", "z"), relevance, List.of(1, 3, 5));

        assertEquals(0.0, m.get("hit_rate@1"), EPS);
        assertEquals(1.0, m.get("hit_rate@3"), EPS);
        assertEquals(1.0 / 3, m.get("recall@3"), EPS);
        assertEquals(2.0 / 3, m.get("recall@5"), EPS);
        assertEquals(1.0 / 3, m.get("precision@3"), EPS);
        assertEquals(2.0 / 5, m.get("precision@5"), EPS);
        assertEquals(0.0, m.get("mrr@1"), EPS);
        assertEquals(0.5, m.get("mrr@5"), EPS);
        // AP@5 = (1/2 + 2/4) / min(3, 5)
        assertEquals((0.5 + 0.5) / 3, m.get("map@5"), EPS);
        // nDCG@5 = (1/log2(3) + 1/log2(5)) / (1 + 1/log2(3) + 1/log2(4))
        double dcg = 1 / log2(3) + 1 / log2(5);
        double idcg = 1 + 1 / log2(3) + 1 / log2(4);
        assertEquals(dcg / idcg, m.get("ndcg@5"), EPS);
    }

    @Test
    void gradedRelevanceRewardsPuttingTheBestDocumentFirst() {
        Map<String, Double> relevance = Map.of("best", 2.0, "ok", 1.0);
        double bestFirst = RankingMetrics.compute(List.of("best", "ok"), relevance, List.of(2)).get("ndcg@2");
        double okFirst = RankingMetrics.compute(List.of("ok", "best"), relevance, List.of(2)).get("ndcg@2");
        assertEquals(1.0, bestFirst, EPS);
        assertEquals((1 + 2 / log2(3)) / (2 + 1 / log2(3)), okFirst, EPS);
    }

    @Test
    void mapIsOneForAPerfectTopKEvenWithMoreRelevantDocuments() {
        Map<String, Double> relevance = Map.of("a", 1.0, "b", 1.0, "c", 1.0, "d", 1.0);
        Map<String, Double> m = RankingMetrics.compute(List.of("a", "b"), relevance, List.of(2));
        assertEquals(1.0, m.get("map@2"), EPS);
        assertEquals(0.5, m.get("recall@2"), EPS);
    }

    @Test
    void zeroGradeDocumentsAreNotRelevant() {
        Map<String, Double> relevance = Map.of("a", 1.0, "distractor", 0.0);
        Map<String, Double> m = RankingMetrics.compute(List.of("distractor", "a"), relevance, List.of(1, 2));
        assertEquals(0.0, m.get("hit_rate@1"), EPS);
        assertEquals(0.5, m.get("mrr@2"), EPS);
        assertEquals(1.0, m.get("recall@2"), EPS);
    }

    @Test
    void emptyResultsScoreZero() {
        Map<String, Double> m = RankingMetrics.compute(List.of(), Map.of("a", 1.0), List.of(5));
        m.values().forEach(v -> assertEquals(0.0, v, EPS));
    }

    @Test
    void namesAreFamilyMajor() {
        assertEquals(List.of("hit_rate@1", "hit_rate@5", "recall@1", "recall@5", "precision@1", "precision@5",
                "mrr@1", "mrr@5", "ndcg@1", "ndcg@5", "map@1", "map@5"), RankingMetrics.names(List.of(1, 5)));
    }
}
