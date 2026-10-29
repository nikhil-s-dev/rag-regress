package io.github.nikhilucumberlands.ragregress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One labelled question: the query text and which documents should come back for it.
 *
 * @param relevance document id to relevance grade. Grades above 0 count as relevant; higher grades
 *                  matter for nDCG (for example 2 for the document that answers the question and 1
 *                  for one that helps).
 * @param tags      optional labels used to break metrics down by slice, e.g. "billing"
 */
public record GoldenQuery(String id, String query, Map<String, Double> relevance, List<String> tags) {

    public GoldenQuery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(query, "query");
        relevance = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(relevance, "relevance")));
        tags = List.copyOf(tags == null ? List.of() : tags);
        if (relevance.values().stream().noneMatch(grade -> grade > 0)) {
            throw new IllegalArgumentException("query " + id + " has no relevant documents");
        }
    }

    /** Convenience for binary relevance: every listed document has grade 1. */
    public static GoldenQuery of(String id, String query, String... relevantIds) {
        Map<String, Double> relevance = new LinkedHashMap<>();
        for (String docId : relevantIds) {
            relevance.put(docId, 1.0);
        }
        return new GoldenQuery(id, query, relevance, List.of());
    }

    public Set<String> relevantIds() {
        Set<String> ids = new LinkedHashSet<>();
        relevance.forEach((docId, grade) -> {
            if (grade > 0) {
                ids.add(docId);
            }
        });
        return ids;
    }

    public double grade(String docId) {
        return relevance.getOrDefault(docId, 0.0);
    }
}
