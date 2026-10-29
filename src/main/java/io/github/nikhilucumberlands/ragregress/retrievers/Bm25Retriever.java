package io.github.nikhilucumberlands.ragregress.retrievers;

import io.github.nikhilucumberlands.ragregress.Retriever;
import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory BM25 (Okapi, Lucene's idf) over a small corpus. Useful as a lexical baseline to compare
 * a vector retriever against, and for trying the tool without any infrastructure. Thread-safe
 * after construction.
 *
 * <p>Tokenization is deliberately simple: lowercase, split on anything that is not a letter or
 * digit, drop common English stopwords, fold plurals ending in "s".
 */
public final class Bm25Retriever implements Retriever {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "get", "how",
            "i", "if", "in", "into", "is", "it", "its", "me", "my", "of", "on", "or", "our", "so", "that", "the",
            "their", "them", "there", "this", "to", "us", "was", "we", "what", "when", "where", "which", "who",
            "why", "will", "with", "you", "your");

    private final double k1;
    private final double b;
    private final List<String> ids;
    private final List<Map<String, Integer>> termFrequencies;
    private final int[] lengths;
    private final double averageLength;
    private final Map<String, List<Integer>> postings = new HashMap<>();
    private final String description;

    /** BM25 with Lucene's defaults, k1 = 1.2 and b = 0.75. */
    public Bm25Retriever(Map<String, String> documents) {
        this(documents, 1.2, 0.75, "bm25 (" + documents.size() + " documents)");
    }

    public Bm25Retriever(Map<String, String> documents, double k1, double b, String description) {
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("BM25 needs at least one document");
        }
        this.k1 = k1;
        this.b = b;
        this.description = description;
        this.ids = new ArrayList<>(documents.keySet());
        this.termFrequencies = new ArrayList<>();
        this.lengths = new int[ids.size()];
        long total = 0;
        for (int i = 0; i < ids.size(); i++) {
            List<String> tokens = tokenize(documents.get(ids.get(i)));
            Map<String, Integer> tf = new HashMap<>();
            tokens.forEach(token -> tf.merge(token, 1, Integer::sum));
            termFrequencies.add(tf);
            lengths[i] = tokens.size();
            total += tokens.size();
            for (String term : tf.keySet()) {
                postings.computeIfAbsent(term, t -> new ArrayList<>()).add(i);
            }
        }
        this.averageLength = Math.max(1.0, (double) total / ids.size());
    }

    /**
     * Loads a JSONL corpus where each line has an {@code id} and text fields, indexing the given
     * fields joined together (for example {@code title} and {@code text}).
     */
    public static Bm25Retriever fromJsonl(Path corpus, List<String> fields) throws IOException {
        Map<String, String> documents = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(corpus);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> doc = Json.parseObject(line);
                Object rawId = doc.get("id");
                if (rawId == null) {
                    throw new JsonException("missing \"id\"");
                }
                StringBuilder text = new StringBuilder();
                for (String field : fields) {
                    String value = Json.string(doc.get(field));
                    if (value != null) {
                        text.append(value).append('\n');
                    }
                }
                if (text.isEmpty()) {
                    throw new JsonException("none of the fields " + fields + " are present");
                }
                documents.put(String.valueOf(rawId), text.toString());
            } catch (JsonException e) {
                throw new IOException(corpus + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (documents.isEmpty()) {
            throw new IOException(corpus + " contains no documents");
        }
        return new Bm25Retriever(documents, 1.2, 0.75,
                "bm25:" + corpus + " (fields " + String.join(",", fields) + ")");
    }

    @Override
    public List<String> retrieve(String query, int k) {
        double[] scores = new double[ids.size()];
        for (String term : new LinkedHashSet<>(tokenize(query))) {
            List<Integer> docs = postings.get(term);
            if (docs == null) {
                continue;
            }
            double idf = Math.log(1 + (ids.size() - docs.size() + 0.5) / (docs.size() + 0.5));
            for (int doc : docs) {
                int tf = termFrequencies.get(doc).get(term);
                double norm = tf + k1 * (1 - b + b * lengths[doc] / averageLength);
                scores[doc] += idf * tf * (k1 + 1) / norm;
            }
        }
        Integer[] order = new Integer[ids.size()];
        Arrays.setAll(order, i -> i);
        return Arrays.stream(order)
                .filter(i -> scores[i] > 0)
                .sorted(Comparator.comparingDouble((Integer i) -> -scores[i]).thenComparing(ids::get))
                .limit(k)
                .map(ids::get)
                .toList();
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty() || STOPWORDS.contains(raw)) {
                continue;
            }
            String token = raw.length() > 3 && raw.endsWith("s") && !raw.endsWith("ss")
                    ? raw.substring(0, raw.length() - 1)
                    : raw;
            tokens.add(token);
        }
        return tokens;
    }

    @Override
    public String toString() {
        return description;
    }
}
