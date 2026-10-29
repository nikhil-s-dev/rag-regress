package io.github.nikhilucumberlands.ragregress;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nikhilucumberlands.ragregress.assertions.RagAssertions;
import io.github.nikhilucumberlands.ragregress.retrievers.Bm25Retriever;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The usage shown in the README, run for real: retrieval quality checks written as ordinary JUnit
 * tests. In your own project, replace the BM25 retriever with a lambda around your vector store.
 */
class ExampleRetrievalQualityTest {

    private static GoldenSet golden;
    private static EvalReport baseline;

    @BeforeAll
    static void load() throws IOException {
        golden = GoldenSet.load(Path.of("examples/golden.jsonl"));
        baseline = EvalReport.load(Path.of("examples/baseline.json"));
    }

    private static EvalReport evaluate(List<String> fields) throws IOException {
        Retriever retriever = Bm25Retriever.fromJsonl(Path.of("examples/corpus.jsonl"), fields);
        return RagEval.builder(retriever).ks(1, 3, 5, 10).build().evaluate(golden);
    }

    @Test
    void retrievalMeetsTheQualityBar() throws IOException {
        EvalReport report = evaluate(List.of("title", "text"));

        RagAssertions.assertThat(report)
                .hasNoErrors()
                .metric("recall@5").isAtLeast(0.90)
                .metric("ndcg@10").isAtLeast(0.90)
                .satisfies("latency_p95<200ms");

        RagAssertions.assertThat(report)
                .comparedTo(baseline)
                .hasNoSignificantRegression();
    }

    @Test
    void aBrokenIndexIsCaught() throws IOException {
        // Simulates a refactor that accidentally stopped indexing document bodies.
        EvalReport titleOnly = evaluate(List.of("title"));

        AssertionError error = assertThrows(AssertionError.class, () ->
                RagAssertions.assertThat(titleOnly).comparedTo(baseline).hasNoSignificantRegression("recall@5"));
        assertTrue(error.getMessage().contains("recall@5"), error.getMessage());
    }
}
