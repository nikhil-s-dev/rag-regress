package io.github.nikhilucumberlands.ragregress.assertions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.GoldenQuery;
import io.github.nikhilucumberlands.ragregress.GoldenSet;
import io.github.nikhilucumberlands.ragregress.RagEval;
import io.github.nikhilucumberlands.ragregress.Retriever;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RagAssertionsTest {

    private static EvalReport evaluate(Set<Integer> misses) {
        List<GoldenQuery> queries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            queries.add(new GoldenQuery("q" + i, "question " + i, Map.of("doc-" + i, 1.0), List.of()));
        }
        Retriever retriever = (query, k) -> {
            int i = Integer.parseInt(query.substring("question ".length()));
            if (i == 29 && misses.contains(-1)) {
                throw new IllegalStateException("search cluster down");
            }
            return misses.contains(i) ? List.of("other") : List.of("doc-" + i);
        };
        return RagEval.builder(retriever).ks(1, 5).build().evaluate(GoldenSet.of("test", queries));
    }

    @Test
    void passingAssertionsChain() {
        EvalReport report = evaluate(Set.of(4));
        assertDoesNotThrow(() -> RagAssertions.assertThat(report)
                .hasNoErrors()
                .metric("recall@5").isAtLeast(0.9)
                .metric("error_rate").isAtMost(0.0)
                .satisfies("ndcg@5>=90%"));
    }

    @Test
    void failuresNameTheWorstQueries() {
        EvalReport report = evaluate(Set.of(4, 9, 12, 13, 14, 15));
        AssertionError error = assertThrows(AssertionError.class,
                () -> RagAssertions.assertThat(report).metric("recall@5").isAtLeast(0.9));
        assertTrue(error.getMessage().startsWith("recall@5 was 0.800, expected at least 0.900 (30 queries)."), error.getMessage());
        assertTrue(error.getMessage().contains("q12 0.000  \"question 12\""), error.getMessage());

        AssertionError threshold = assertThrows(AssertionError.class,
                () -> RagAssertions.assertThat(report).satisfies("mrr@1>=0.95"));
        assertTrue(threshold.getMessage().contains("Lowest-scoring queries"), threshold.getMessage());
    }

    @Test
    void retrievalErrorsAreSurfaced() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> RagAssertions.assertThat(evaluate(Set.of(-1))).hasNoErrors());
        assertTrue(error.getMessage().contains("q29: IllegalStateException: search cluster down"), error.getMessage());
    }

    @Test
    void baselineComparisonFailsOnlyOnSignificantDrops() {
        EvalReport baseline = evaluate(Set.of());
        assertDoesNotThrow(() -> RagAssertions.assertThat(evaluate(Set.of(3))).comparedTo(baseline).hasNoSignificantRegression());

        EvalReport broken = evaluate(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        AssertionError error = assertThrows(AssertionError.class,
                () -> RagAssertions.assertThat(broken).comparedTo(baseline).hasNoSignificantRegression("recall@5"));
        assertTrue(error.getMessage().contains("recall@5 1.000 -> 0.667"), error.getMessage());
        assertTrue(error.getMessage().contains("lost: doc-0"), error.getMessage());
    }

    @Test
    void unknownMetricsAreAUsageError() {
        EvalReport report = evaluate(Set.of());
        assertThrows(IllegalArgumentException.class, () -> RagAssertions.assertThat(report).metric("recall@10"));
    }
}
