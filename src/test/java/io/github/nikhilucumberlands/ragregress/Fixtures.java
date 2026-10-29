package io.github.nikhilucumberlands.ragregress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small in-memory golden sets and retrievers for tests. */
final class Fixtures {

    private Fixtures() {}

    /** n queries; query i has the single relevant document "doc-i". */
    static GoldenSet golden(int n) {
        List<GoldenQuery> queries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            queries.add(new GoldenQuery("q" + i, "question " + i, Map.of("doc-" + i, 1.0), List.of(i % 2 == 0 ? "even" : "odd")));
        }
        return GoldenSet.of("fixture", queries);
    }

    /** Returns the right document first for every query, then filler. */
    static Retriever perfect() {
        return (query, k) -> List.of("doc-" + query.substring("question ".length()), "filler-1", "filler-2");
    }

    /** Like {@link #perfect()} but misses the queries whose number is in {@code misses}. */
    static Retriever missing(int... misses) {
        Map<String, Boolean> miss = new HashMap<>();
        for (int m : misses) {
            miss.put("question " + m, true);
        }
        return (query, k) -> miss.containsKey(query)
                ? List.of("filler-1", "filler-2", "filler-3")
                : List.of("doc-" + query.substring("question ".length()), "filler-1");
    }
}
