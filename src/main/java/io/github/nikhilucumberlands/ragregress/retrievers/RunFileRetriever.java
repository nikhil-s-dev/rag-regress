package io.github.nikhilucumberlands.ragregress.retrievers;

import io.github.nikhilucumberlands.ragregress.GoldenQuery;
import io.github.nikhilucumberlands.ragregress.Retriever;
import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Replays results that were computed elsewhere, for example exported from a notebook or a batch
 * job. JSONL with one line per query: {@code {"id": "q01", "retrieved": ["doc-3", "doc-9"]}}.
 * Lines are matched to golden queries by {@code id}, or by {@code query} text when there is no id.
 * A golden query with no line in the file is reported as an error, not silently skipped.
 */
public final class RunFileRetriever implements Retriever {

    private final Map<String, List<String>> byId;
    private final Map<String, List<String>> byQuery;
    private final String description;

    private RunFileRetriever(Map<String, List<String>> byId, Map<String, List<String>> byQuery, String description) {
        this.byId = byId;
        this.byQuery = byQuery;
        this.description = description;
    }

    public static RunFileRetriever load(Path path) throws IOException {
        Map<String, List<String>> byId = new HashMap<>();
        Map<String, List<String>> byQuery = new HashMap<>();
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> entry = Json.parseObject(line);
                List<Object> rawIds = Json.asList(entry.get("retrieved"));
                if (rawIds == null) {
                    throw new JsonException("missing \"retrieved\" list");
                }
                List<String> retrieved = new ArrayList<>();
                rawIds.forEach(id -> retrieved.add(HttpRetriever.idString(id)));
                Object id = entry.get("id");
                String query = Json.string(entry.get("query"));
                if (id == null && query == null) {
                    throw new JsonException("each line needs an \"id\" or a \"query\"");
                }
                if (id != null) {
                    byId.put(String.valueOf(id), retrieved);
                }
                if (query != null) {
                    byQuery.put(query, retrieved);
                }
            } catch (JsonException e) {
                throw new IOException(path + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return new RunFileRetriever(byId, byQuery, "run:" + path);
    }

    @Override
    public List<String> retrieve(String query, int k) {
        List<String> results = byQuery.get(query);
        if (results == null) {
            throw new NoSuchElementException("run file has no results for this query text");
        }
        return results.subList(0, Math.min(k, results.size()));
    }

    @Override
    public List<String> retrieve(GoldenQuery query, int k) {
        List<String> results = byId.containsKey(query.id()) ? byId.get(query.id()) : byQuery.get(query.query());
        if (results == null) {
            throw new NoSuchElementException("run file has no results for query " + query.id());
        }
        return results.subList(0, Math.min(k, results.size()));
    }

    @Override
    public String toString() {
        return description;
    }
}
