package io.github.nikhilucumberlands.ragregress.mlflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.nikhilucumberlands.ragregress.util.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a fake MLflow server that records the REST calls it receives. */
class MlflowClientTest {

    private record Call(String method, String path, String body, String authorization) {}

    private HttpServer server;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean experimentExists;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/2.0/mlflow/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        calls.add(new Call(exchange.getRequestMethod(), path, body, exchange.getRequestHeaders().getFirst("Authorization")));
        String response;
        int status = 200;
        if (path.startsWith("/api/2.0/mlflow/experiments/get-by-name")) {
            if (experimentExists) {
                response = "{\"experiment\":{\"experiment_id\":\"7\",\"name\":\"rag\"}}";
            } else {
                status = 404;
                response = "{\"error_code\":\"RESOURCE_DOES_NOT_EXIST\",\"message\":\"no such experiment\"}";
            }
        } else if (path.equals("/api/2.0/mlflow/experiments/create")) {
            response = "{\"experiment_id\":\"12\"}";
        } else if (path.equals("/api/2.0/mlflow/runs/create")) {
            response = "{\"run\":{\"info\":{\"run_id\":\"run-abc\",\"experiment_id\":\"12\"}}}";
        } else if (path.equals("/api/2.0/mlflow/runs/log-batch") || path.equals("/api/2.0/mlflow/runs/update")) {
            response = "{}";
        } else {
            status = 404;
            response = "{}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void createsTheExperimentAndLogsAFinishedRun() throws Exception {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("recall@5", 0.875);
        metrics.put("latency_p95_s", Double.NaN);
        String runId = new MlflowClient(uri(), Map.of("MLFLOW_TRACKING_TOKEN", "t0ken"))
                .logRun("rag eval", "nightly", Map.of("retriever", "bm25"), metrics, Map.of("rag_regress.passed", "true"));

        assertEquals("run-abc", runId);
        assertEquals(List.of(
                "GET /api/2.0/mlflow/experiments/get-by-name?experiment_name=rag+eval",
                "POST /api/2.0/mlflow/experiments/create",
                "POST /api/2.0/mlflow/runs/create",
                "POST /api/2.0/mlflow/runs/log-batch",
                "POST /api/2.0/mlflow/runs/update"),
                calls.stream().map(c -> c.method() + " " + c.path()).toList());
        calls.forEach(c -> assertEquals("Bearer t0ken", c.authorization()));

        Map<String, Object> create = Json.parseObject(calls.get(2).body());
        assertEquals("12", create.get("experiment_id"));
        assertEquals("nightly", create.get("run_name"));

        Map<String, Object> batch = Json.parseObject(calls.get(3).body());
        List<Object> logged = Json.asList(batch.get("metrics"));
        assertEquals(1, logged.size(), "NaN metrics are skipped");
        assertEquals("recall_at_5", Json.asObject(logged.get(0)).get("key"));
        assertEquals(0.875, Json.asObject(logged.get(0)).get("value"));

        assertEquals("FINISHED", Json.parseObject(calls.get(4).body()).get("status"));
    }

    @Test
    void reusesAnExistingExperimentWithBasicAuth() throws Exception {
        experimentExists = true;
        new MlflowClient(uri(), Map.of("MLFLOW_TRACKING_USERNAME", "u", "MLFLOW_TRACKING_PASSWORD", "p"))
                .logRun("rag", null, Map.of(), Map.of("ndcg@10", 0.5), Map.of());
        assertEquals("POST /api/2.0/mlflow/runs/create", calls.get(1).method() + " " + calls.get(1).path());
        assertEquals("7", Json.parseObject(calls.get(1).body()).get("experiment_id"));
        assertTrue(calls.get(0).authorization().startsWith("Basic "));
    }

    @Test
    void sanitizesKeysAndRejectsNonHttpUris() {
        assertEquals("delta_ndcg_at_10", MlflowClient.metricKey("delta_ndcg@10"));
        assertEquals("a_b", MlflowClient.metricKey("a#b"));
        assertThrows(IllegalArgumentException.class, () -> new MlflowClient(URI.create("file:///tmp/mlruns"), Map.of()));
    }
}
