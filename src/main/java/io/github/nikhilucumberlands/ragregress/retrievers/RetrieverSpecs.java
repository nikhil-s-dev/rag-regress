package io.github.nikhilucumberlands.ragregress.retrievers;

import io.github.nikhilucumberlands.ragregress.Retriever;
import io.github.nikhilucumberlands.ragregress.util.JsonPath;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builds a retriever from a command-line spec:
 *
 * <ul>
 *   <li>{@code bm25:corpus.jsonl}: in-memory BM25 over a JSONL corpus</li>
 *   <li>{@code http://host/search} or {@code https://...}: an HTTP retrieval service</li>
 *   <li>{@code run:results.jsonl}: precomputed results</li>
 *   <li>{@code class:com.acme.MyRetriever}: a class on the classpath implementing
 *       {@link Retriever} with a public no-argument constructor</li>
 * </ul>
 */
public final class RetrieverSpecs {

    /** Settings that only apply to some kinds of retriever. */
    public record Settings(List<String> bm25Fields, Object httpBody, String idPath,
                           Map<String, String> headers, Duration timeout) {

        public static Settings defaults() {
            return new Settings(List.of("title", "text"), null, "results[*].id", Map.of(), Duration.ofSeconds(30));
        }
    }

    /** A retriever and the label reports use for it. */
    public record Created(Retriever retriever, String description) {}

    private RetrieverSpecs() {}

    public static Created create(String spec, Settings settings) throws IOException {
        String value = spec.strip();
        if (value.startsWith("bm25:")) {
            Bm25Retriever bm25 = Bm25Retriever.fromJsonl(Path.of(value.substring(5)), settings.bm25Fields());
            return new Created(bm25, bm25.toString());
        }
        if (value.startsWith("run:")) {
            RunFileRetriever run = RunFileRetriever.load(Path.of(value.substring(4)));
            return new Created(run, run.toString());
        }
        if (value.startsWith("http:") && !value.startsWith("http://")) {
            value = value.substring(5);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpRetriever http = new HttpRetriever(URI.create(value), settings.httpBody(),
                    JsonPath.compile(settings.idPath()), settings.headers(), settings.timeout());
            return new Created(http, http.toString());
        }
        if (value.startsWith("class:")) {
            String className = value.substring(6);
            return new Created(instantiate(className), "class:" + className);
        }
        throw new IllegalArgumentException("unknown retriever '" + spec
                + "'; use bm25:FILE, run:FILE, http(s)://URL or class:NAME");
    }

    private static Retriever instantiate(String className) {
        try {
            Class<?> type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            if (!Retriever.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException(className + " does not implement " + Retriever.class.getName());
            }
            return (Retriever) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("class " + className + " not found; add its jar with java -cp", e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(className + " needs a public no-argument constructor", e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException("cannot create " + className + ": " + e, e);
        }
    }
}
