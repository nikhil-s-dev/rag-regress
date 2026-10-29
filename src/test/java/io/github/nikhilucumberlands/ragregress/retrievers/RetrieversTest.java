package io.github.nikhilucumberlands.ragregress.retrievers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.nikhilucumberlands.ragregress.GoldenQuery;
import io.github.nikhilucumberlands.ragregress.Retriever;
import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonPath;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrieversTest {

    @Test
    void bm25RanksByTermWeight() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("keys", "Rotate API keys regularly. API keys authenticate calls.");
        docs.put("billing", "Invoices are emailed monthly.");
        docs.put("mixed", "Billing questions about API usage.");
        Bm25Retriever bm25 = new Bm25Retriever(docs);

        assertEquals(List.of("keys", "mixed"), bm25.retrieve("how do I rotate my API key?", 5));
        assertEquals(List.of("billing"), bm25.retrieve("monthly invoices", 1));
        assertEquals(List.of(), bm25.retrieve("the and of", 5), "stopwords only");
    }

    @Test
    void bm25TokenizerFoldsPluralsAndDropsStopwords() {
        assertEquals(List.of("api", "key", "class", "2fa"), Bm25Retriever.tokenize("The API keys, class & 2FA"));
    }

    @Test
    void bm25LoadsSelectedFieldsFromJsonl(@TempDir Path dir) throws IOException {
        Path corpus = dir.resolve("corpus.jsonl");
        Files.writeString(corpus, """
                {"id": "d1", "title": "Webhooks", "text": "Signed with HMAC"}
                {"id": "d2", "title": "Retries", "text": "Deliveries retried with backoff"}
                """);
        assertEquals(List.of("d1"), Bm25Retriever.fromJsonl(corpus, List.of("title", "text")).retrieve("hmac signature", 3));
        assertEquals(List.of(), Bm25Retriever.fromJsonl(corpus, List.of("title")).retrieve("hmac signature", 3));
        IOException e = assertThrows(IOException.class, () -> Bm25Retriever.fromJsonl(corpus, List.of("body")));
        assertTrue(e.getMessage().contains("line 1"), e.getMessage());
    }

    @Test
    void httpRetrieverFillsTheTemplateAndReadsIds() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " | " + exchange.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] body = exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("fail")
                    ? "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8)
                    : "{\"data\":{\"matches\":[{\"doc_id\":17},{\"doc_id\":\"a-2\"}]}}".getBytes(StandardCharsets.UTF_8);
            int status = exchange.getRequestURI().getQuery() != null ? 500 : 200;
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
            Object template = Json.parse("{\"q\": \"{query}\", \"top_k\": \"{k}\", \"note\": \"k={k}\", \"filters\": [\"public\"]}");
            HttpRetriever retriever = new HttpRetriever(URI.create(base), template, JsonPath.compile("data.matches[*].doc_id"),
                    Map.of("X-Api-Key", "abc"), Duration.ofSeconds(5));

            assertEquals(List.of("17", "a-2"), retriever.retrieve("reset \"password\"", 3));
            String[] parts = seen.get().split(" \\| ");
            assertEquals(Map.of("q", "reset \"password\"", "top_k", 3L, "note", "k=3", "filters", List.of("public")),
                    Json.parseObject(parts[0]));
            assertEquals("abc", parts[1]);

            HttpRetriever failing = new HttpRetriever(URI.create(base + "?fail"), null, JsonPath.compile("results[*].id"),
                    Map.of(), Duration.ofSeconds(5));
            IOException e = assertThrows(IOException.class, () -> failing.retrieve("x", 1));
            assertTrue(e.getMessage().startsWith("HTTP 500"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runFileMatchesByIdOrQueryAndFlagsMissingQueries(@TempDir Path dir) throws IOException {
        Path run = dir.resolve("run.jsonl");
        Files.writeString(run, """
                {"id": "q1", "retrieved": ["d3", "d1", "d2"]}
                {"query": "only text", "retrieved": [5, 6]}
                """);
        RunFileRetriever retriever = RunFileRetriever.load(run);
        assertEquals(List.of("d3", "d1"), retriever.retrieve(GoldenQuery.of("q1", "anything", "d1"), 2));
        assertEquals(List.of("5", "6"), retriever.retrieve(GoldenQuery.of("q2", "only text", "d1"), 5));
        assertThrows(NoSuchElementException.class, () -> retriever.retrieve(GoldenQuery.of("q3", "missing", "d1"), 5));
    }

    @Test
    void specsBuildEachKindOfRetriever(@TempDir Path dir) throws IOException {
        Path corpus = dir.resolve("c.jsonl");
        Files.writeString(corpus, "{\"id\": \"d1\", \"title\": \"t\", \"text\": \"hello world\"}\n");
        RetrieverSpecs.Settings settings = RetrieverSpecs.Settings.defaults();

        assertTrue(RetrieverSpecs.create("bm25:" + corpus, settings).description().startsWith("bm25:"));
        assertTrue(RetrieverSpecs.create("http://localhost:9/search", settings).retriever() instanceof HttpRetriever);
        assertTrue(RetrieverSpecs.create("http:https://example.test/q", settings).description().startsWith("https://example.test/q"));
        Retriever fromClass = RetrieverSpecs.create("class:" + EchoRetriever.class.getName(), settings).retriever();
        assertTrue(fromClass instanceof EchoRetriever);

        assertThrows(IllegalArgumentException.class, () -> RetrieverSpecs.create("ftp://x", settings));
        assertThrows(IllegalArgumentException.class, () -> RetrieverSpecs.create("class:java.lang.String", settings));
        assertThrows(IllegalArgumentException.class, () -> RetrieverSpecs.create("class:com.example.Missing", settings));
    }

    /** Loaded by name in {@link #specsBuildEachKindOfRetriever}. */
    public static final class EchoRetriever implements Retriever {
        @Override
        public List<String> retrieve(String query, int k) {
            return List.of(query);
        }
    }
}
