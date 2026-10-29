package io.github.nikhilucumberlands.ragregress;

import java.util.List;

/**
 * The system under test: anything that turns a query into a ranked list of document ids.
 * Wrapping an existing component is usually one lambda, for example with Spring AI:
 *
 * <pre>{@code
 * Retriever retriever = (query, k) -> vectorStore
 *         .similaritySearch(SearchRequest.builder().query(query).topK(k).build())
 *         .stream().map(Document::getId).toList();
 * }</pre>
 *
 * Implementations are called from several threads when evaluation concurrency is above 1.
 */
@FunctionalInterface
public interface Retriever {

    /** Returns up to {@code k} document ids, best match first. */
    List<String> retrieve(String query, int k) throws Exception;

    /**
     * Called by the evaluator with the full golden-set entry. Override when results are looked up by
     * query id rather than computed from the text, as {@code RunFileRetriever} does.
     */
    default List<String> retrieve(GoldenQuery query, int k) throws Exception {
        return retrieve(query.query(), k);
    }
}
