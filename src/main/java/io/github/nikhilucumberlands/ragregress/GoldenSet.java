package io.github.nikhilucumberlands.ragregress;

import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A labelled evaluation set, usually loaded from JSONL with one query per line:
 *
 * <pre>{@code
 * {"id": "q01", "query": "How do I rotate an API key?", "relevant": ["doc-api-keys"]}
 * {"id": "q02", "query": "Recover a dropped table", "relevant": {"doc-restore": 2, "doc-backups": 1}, "tags": ["data"]}
 * }</pre>
 *
 * {@code relevant} is either a list of document ids (all grade 1) or an object of id to grade.
 */
public final class GoldenSet implements Iterable<GoldenQuery> {

    private final String name;
    private final List<GoldenQuery> queries;

    private GoldenSet(String name, List<GoldenQuery> queries) {
        this.name = name;
        this.queries = List.copyOf(queries);
    }

    public static GoldenSet of(String name, List<GoldenQuery> queries) {
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("a golden set needs at least one query");
        }
        Set<String> seen = new HashSet<>();
        for (GoldenQuery query : queries) {
            if (!seen.add(query.id())) {
                throw new IllegalArgumentException("duplicate query id " + query.id());
            }
        }
        return new GoldenSet(name, queries);
    }

    /** Loads a JSONL golden set, reporting the line number of the first bad entry. */
    public static GoldenSet load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<GoldenQuery> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                GoldenQuery query = parse(Json.parseObject(line));
                if (!seen.add(query.id())) {
                    throw new IllegalArgumentException("duplicate id '" + query.id() + "'");
                }
                queries.add(query);
            } catch (JsonException | IllegalArgumentException e) {
                throw new IOException(path + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (queries.isEmpty()) {
            throw new IOException(path + " contains no queries");
        }
        return new GoldenSet(path.toString(), queries);
    }

    private static GoldenQuery parse(Map<String, Object> entry) {
        Object rawId = entry.get("id");
        String id = rawId instanceof String s ? s : rawId instanceof Number ? String.valueOf(rawId) : null;
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("missing \"id\"");
        }
        String query = Json.string(entry.get("query"));
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query " + id + ": missing \"query\" text");
        }
        Map<String, Double> relevance = new LinkedHashMap<>();
        Object relevant = entry.get("relevant");
        if (relevant instanceof List<?> list) {
            for (Object docId : list) {
                relevance.put(docId(docId, id), 1.0);
            }
        } else if (relevant instanceof Map<?, ?> grades) {
            for (Map.Entry<?, ?> grade : grades.entrySet()) {
                double value = Json.number(grade.getValue());
                if (Double.isNaN(value) || value < 0) {
                    throw new IllegalArgumentException("query " + id + ": grade for " + grade.getKey()
                            + " must be a number of 0 or more");
                }
                relevance.put(String.valueOf(grade.getKey()), value);
            }
        } else {
            throw new IllegalArgumentException("query " + id + ": \"relevant\" must be a list of ids or an object of id to grade");
        }
        List<String> tags = new ArrayList<>();
        List<Object> rawTags = Json.asList(entry.get("tags"));
        if (rawTags != null) {
            for (Object tag : rawTags) {
                if (!(tag instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("query " + id + ": tags must be non-empty strings");
                }
                tags.add(text);
            }
        }
        return new GoldenQuery(id, query, relevance, tags);
    }

    private static String docId(Object value, String queryId) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("query " + queryId + ": relevant ids must be strings");
    }

    public String name() {
        return name;
    }

    public List<GoldenQuery> queries() {
        return queries;
    }

    public int size() {
        return queries.size();
    }

    @Override
    public Iterator<GoldenQuery> iterator() {
        return queries.iterator();
    }
}
