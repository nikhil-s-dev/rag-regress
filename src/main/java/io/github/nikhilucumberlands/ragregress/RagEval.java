package io.github.nikhilucumberlands.ragregress;

import io.github.nikhilucumberlands.ragregress.metrics.RankingMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Runs a {@link Retriever} over a {@link GoldenSet} and scores every query.
 *
 * <pre>{@code
 * EvalReport report = RagEval.builder(retriever).ks(1, 5, 10).build().evaluate(GoldenSet.load(path));
 * RagAssertions.assertThat(report).metric("recall@5").isAtLeast(0.8);
 * }</pre>
 *
 * A query whose retrieval throws is kept, scored 0 on every metric and counted in
 * {@code error_rate}, so outages show up as regressions instead of silently shrinking the set.
 */
public final class RagEval {

    private final Retriever retriever;
    private final String retrieverName;
    private final List<Integer> ks;
    private final int concurrency;

    private RagEval(Builder builder) {
        this.retriever = builder.retriever;
        this.retrieverName = builder.name;
        this.ks = builder.ks;
        this.concurrency = builder.concurrency;
    }

    public static Builder builder(Retriever retriever) {
        return new Builder(retriever);
    }

    public EvalReport evaluate(GoldenSet golden) {
        int maxK = ks.get(ks.size() - 1);
        List<GoldenQuery> queries = golden.queries();
        QueryResult[] results = new QueryResult[queries.size()];
        Semaphore permits = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < queries.size(); i++) {
                int index = i;
                executor.execute(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        results[index] = evaluateOne(queries.get(index), maxK);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        return EvalReport.of(version(), Instant.now(), retrieverName, golden.name(), ks, Arrays.asList(results));
    }

    private QueryResult evaluateOne(GoldenQuery query, int maxK) {
        long start = System.nanoTime();
        List<String> raw;
        String error = null;
        try {
            raw = retriever.retrieve(query, maxK);
            if (raw == null) {
                raw = List.of();
            }
        } catch (Exception e) {
            raw = List.of();
            String message = e.getMessage();
            error = e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        }
        double latency = (System.nanoTime() - start) / 1e9;
        List<String> ranked = normalize(raw, maxK);
        return new QueryResult(query.id(), query.query(), query.tags(), query.relevance(), ranked,
                error == null ? RankingMetrics.compute(ranked, query.relevance(), ks) : RankingMetrics.zeros(ks),
                latency, error);
    }

    /** Drops blanks and duplicates (keeping the first occurrence) and cuts to {@code maxK}. */
    static List<String> normalize(List<String> raw, int maxK) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) {
                seen.add(id);
                if (seen.size() == maxK) {
                    break;
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /** Library version from the jar manifest, or "dev" when running from classes. */
    public static String version() {
        String version = RagEval.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    public static final class Builder {
        private final Retriever retriever;
        private String name = "retriever";
        private List<Integer> ks = List.of(1, 5, 10);
        private int concurrency = 4;

        private Builder(Retriever retriever) {
            this.retriever = Objects.requireNonNull(retriever, "retriever");
        }

        /** Cutoffs to compute metrics at, e.g. {@code ks(1, 5, 10)}. */
        public Builder ks(int... values) {
            if (values.length == 0) {
                throw new IllegalArgumentException("need at least one k");
            }
            for (int k : values) {
                if (k < 1) {
                    throw new IllegalArgumentException("k must be at least 1, got " + k);
                }
            }
            ks = Arrays.stream(values).distinct().sorted().boxed().toList();
            return this;
        }

        /** Queries in flight at once (default 4). The retriever must be thread-safe above 1. */
        public Builder concurrency(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("concurrency must be at least 1");
            }
            concurrency = value;
            return this;
        }

        /** Label for reports and baselines, e.g. "pgvector hnsw ef=64". */
        public Builder name(String value) {
            name = Objects.requireNonNull(value, "name");
            return this;
        }

        public RagEval build() {
            return new RagEval(this);
        }
    }
}
