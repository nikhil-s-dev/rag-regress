package io.github.nikhilucumberlands.ragregress;

import io.github.nikhilucumberlands.ragregress.metrics.RankingMetrics;
import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonException;
import io.github.nikhilucumberlands.ragregress.util.Stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Results of evaluating one retriever on one golden set: per-query scores, their means, a
 * breakdown by tag and retrieval latency. Saved as JSON, a report doubles as the baseline for the
 * next run.
 *
 * @param aggregate mean of each metric over all queries (failed queries count as 0)
 */
public record EvalReport(
        String version,
        Instant createdAt,
        String retriever,
        String dataset,
        List<Integer> ks,
        List<QueryResult> queries,
        Map<String, Double> aggregate,
        Map<String, TagSummary> byTag,
        LatencySummary latency,
        int errors) {

    /** Mean metrics for the queries carrying one tag. */
    public record TagSummary(int queries, Map<String, Double> metrics) {
        public TagSummary {
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        }
    }

    /** Retrieval latency over successful queries, in seconds; NaN when nothing was measured. */
    public record LatencySummary(int measured, double p50, double p95, double max) {}

    /** Metric names that are not ranking metrics but can still be used in thresholds. */
    public static final List<String> OPERATIONAL_METRICS = List.of("error_rate", "latency_p50", "latency_p95", "latency_max");

    public EvalReport {
        ks = List.copyOf(ks);
        queries = List.copyOf(queries);
        aggregate = Collections.unmodifiableMap(new LinkedHashMap<>(aggregate));
        byTag = Collections.unmodifiableMap(new LinkedHashMap<>(byTag));
    }

    /** Builds a report from per-query results, computing the aggregates. */
    public static EvalReport of(String version, Instant createdAt, String retriever, String dataset,
                                List<Integer> ks, List<QueryResult> queries) {
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("a report needs at least one query");
        }
        List<String> names = RankingMetrics.names(ks);
        Map<String, Double> aggregate = means(queries, names);

        Map<String, List<QueryResult>> tagged = new TreeMap<>();
        for (QueryResult q : queries) {
            for (String tag : q.tags()) {
                tagged.computeIfAbsent(tag, t -> new ArrayList<>()).add(q);
            }
        }
        Map<String, TagSummary> byTag = new LinkedHashMap<>();
        tagged.forEach((tag, list) -> byTag.put(tag, new TagSummary(list.size(), means(list, names))));

        double[] latencies = Stats.sortedFinite(queries.stream()
                .filter(q -> !q.failed())
                .mapToDouble(QueryResult::latencySeconds)
                .toArray());
        LatencySummary latency = new LatencySummary(latencies.length,
                Stats.percentile(latencies, 50), Stats.percentile(latencies, 95), Stats.percentile(latencies, 100));
        int errors = (int) queries.stream().filter(QueryResult::failed).count();
        return new EvalReport(version, createdAt, retriever, dataset, ks, queries, aggregate, byTag, latency, errors);
    }

    private static Map<String, Double> means(List<QueryResult> queries, List<String> names) {
        Map<String, Double> means = new LinkedHashMap<>();
        for (String name : names) {
            means.put(name, queries.stream().mapToDouble(q -> q.metric(name)).average().orElse(Double.NaN));
        }
        return means;
    }

    public int size() {
        return queries.size();
    }

    public double errorRate() {
        return (double) errors / queries.size();
    }

    /** Ranking metric names in report order, e.g. hit_rate@1 ... map@10. */
    public List<String> metricNames() {
        return RankingMetrics.names(ks);
    }

    /**
     * Value of a ranking metric ({@code recall@5}) or an operational one ({@code error_rate},
     * {@code latency_p95} in seconds).
     */
    public double metric(String name) {
        Double value = aggregate.get(name);
        if (value != null) {
            return value;
        }
        return switch (name) {
            case "error_rate" -> errorRate();
            case "latency_p50" -> latency.p50();
            case "latency_p95" -> latency.p95();
            case "latency_max" -> latency.max();
            default -> throw new IllegalArgumentException("unknown metric '" + name + "'; available: "
                    + String.join(", ", metricNames()) + ", " + String.join(", ", OPERATIONAL_METRICS));
        };
    }

    public boolean hasMetric(String name) {
        return aggregate.containsKey(name) || OPERATIONAL_METRICS.contains(name);
    }

    // ---- JSON ---------------------------------------------------------------------------------

    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "rag-regress");
        root.put("version", version);
        root.put("created_at", createdAt.toString());
        root.put("retriever", retriever);
        root.put("dataset", dataset);
        root.put("ks", ks);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queries", queries.size());
        summary.put("errors", errors);
        summary.put("error_rate", round(errorRate()));
        Map<String, Object> latencyJson = new LinkedHashMap<>();
        latencyJson.put("measured", latency.measured());
        latencyJson.put("p50", round(latency.p50()));
        latencyJson.put("p95", round(latency.p95()));
        latencyJson.put("max", round(latency.max()));
        summary.put("latency_s", latencyJson);
        summary.put("metrics", rounded(aggregate));
        root.put("summary", summary);

        Map<String, Object> tags = new LinkedHashMap<>();
        byTag.forEach((tag, s) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("queries", s.queries());
            entry.put("metrics", rounded(s.metrics()));
            tags.put(tag, entry);
        });
        root.put("by_tag", tags);

        List<Object> list = new ArrayList<>();
        for (QueryResult q : queries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", q.id());
            entry.put("query", q.query());
            entry.put("tags", q.tags());
            entry.put("relevant", q.relevance());
            entry.put("retrieved", q.retrieved());
            entry.put("metrics", rounded(q.metrics()));
            entry.put("latency_s", round(q.latencySeconds()));
            entry.put("error", q.error());
            list.add(entry);
        }
        root.put("queries", list);
        return root;
    }

    public static EvalReport fromJson(Map<String, Object> root) {
        List<Integer> ks = new ArrayList<>();
        List<Object> rawKs = Json.asList(root.get("ks"));
        if (rawKs == null || rawKs.isEmpty()) {
            throw new JsonException("report has no \"ks\"; is this a rag-regress report?");
        }
        for (Object k : rawKs) {
            double value = Json.number(k);
            if (!(value >= 1)) {
                throw new JsonException("\"ks\" must hold positive integers");
            }
            ks.add((int) value);
        }
        List<Object> rawQueries = Json.asList(root.get("queries"));
        if (rawQueries == null || rawQueries.isEmpty()) {
            throw new JsonException("report has no \"queries\"");
        }
        List<QueryResult> queries = new ArrayList<>();
        for (Object raw : rawQueries) {
            Map<String, Object> q = Json.asObject(raw);
            if (q == null) {
                throw new JsonException("each entry in \"queries\" must be an object");
            }
            queries.add(new QueryResult(
                    String.valueOf(q.get("id")),
                    String.valueOf(q.getOrDefault("query", "")),
                    strings(q.get("tags")),
                    numbers(q.get("relevant")),
                    strings(q.get("retrieved")),
                    numbers(q.get("metrics")),
                    q.get("latency_s") == null ? Double.NaN : Json.number(q.get("latency_s")),
                    Json.string(q.get("error"))));
        }
        Instant created;
        try {
            created = Instant.parse(String.valueOf(root.get("created_at")));
        } catch (DateTimeParseException e) {
            created = Instant.EPOCH;
        }
        return of(Json.string(root.get("version")), created, Json.string(root.get("retriever")),
                Json.string(root.get("dataset")), ks, queries);
    }

    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, Json.pretty(toJson()));
    }

    public static EvalReport load(Path path) throws IOException {
        try {
            return fromJson(Json.parseObject(Files.readString(path)));
        } catch (JsonException | IllegalArgumentException e) {
            throw new IOException("cannot read report " + path + ": " + e.getMessage(), e);
        }
    }

    private static List<String> strings(Object value) {
        List<Object> list = Json.asList(value);
        List<String> result = new ArrayList<>();
        if (list != null) {
            list.forEach(item -> result.add(String.valueOf(item)));
        }
        return result;
    }

    private static Map<String, Double> numbers(Object value) {
        Map<String, Object> map = Json.asObject(value);
        Map<String, Double> result = new LinkedHashMap<>();
        if (map != null) {
            map.forEach((key, number) -> result.put(key, Json.number(number)));
        }
        return result;
    }

    private static Map<String, Object> rounded(Map<String, Double> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, round(value)));
        return result;
    }

    static Double round(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
