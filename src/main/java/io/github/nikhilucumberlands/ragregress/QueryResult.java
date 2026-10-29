package io.github.nikhilucumberlands.ragregress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the retriever returned for one golden query and how it scored.
 *
 * @param retrieved      ids returned, best first, de-duplicated and cut to the largest k
 * @param latencySeconds time the retriever took, NaN when not measured
 * @param error          why retrieval failed, or null; failed queries score 0 on every metric
 */
public record QueryResult(
        String id,
        String query,
        List<String> tags,
        Map<String, Double> relevance,
        List<String> retrieved,
        Map<String, Double> metrics,
        double latencySeconds,
        String error) {

    public QueryResult {
        tags = List.copyOf(tags);
        relevance = Collections.unmodifiableMap(new LinkedHashMap<>(relevance));
        retrieved = List.copyOf(retrieved);
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    public boolean failed() {
        return error != null;
    }

    public double metric(String name) {
        Double value = metrics.get(name);
        if (value == null) {
            throw new IllegalArgumentException("query " + id + " has no metric " + name);
        }
        return value;
    }
}
