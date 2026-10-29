package io.github.nikhilucumberlands.ragregress.mlflow;

import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonException;
import io.github.nikhilucumberlands.ragregress.util.Text;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs an evaluation to an MLflow tracking server through its REST API, so Java pipelines can
 * track retrieval quality next to model experiments without a Python client.
 *
 * <p>Authentication follows the MLflow clients' environment variables: {@code MLFLOW_TRACKING_TOKEN}
 * (bearer) or {@code MLFLOW_TRACKING_USERNAME} and {@code MLFLOW_TRACKING_PASSWORD} (basic).
 */
public final class MlflowClient {

    private final String base;
    private final String authorization;
    private final HttpClient http;

    public MlflowClient(URI trackingUri, Map<String, String> env) {
        String scheme = trackingUri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("MLflow tracking URI must be http(s), got " + trackingUri);
        }
        String text = trackingUri.toString();
        this.base = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
        String token = env.get("MLFLOW_TRACKING_TOKEN");
        String user = env.get("MLFLOW_TRACKING_USERNAME");
        String password = env.get("MLFLOW_TRACKING_PASSWORD");
        if (token != null && !token.isBlank()) {
            authorization = "Bearer " + token;
        } else if (user != null && password != null) {
            authorization = "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        } else {
            authorization = null;
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Creates a finished run with the given params, metrics and tags, creating the experiment if
     * needed. Metric keys are sanitized (MLflow does not allow {@code @}).
     *
     * @return the run id
     */
    public String logRun(String experiment, String runName, Map<String, String> params,
                         Map<String, Double> metrics, Map<String, String> tags) throws IOException, InterruptedException {
        String experimentId = experimentId(experiment);
        long now = System.currentTimeMillis();

        List<Object> runTags = new ArrayList<>();
        runTags.add(tag("mlflow.source.name", "rag-regress"));
        if (runName != null) {
            runTags.add(tag("mlflow.runName", runName));
        }
        tags.forEach((key, value) -> runTags.add(tag(key, value)));
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("experiment_id", experimentId);
        create.put("start_time", now);
        if (runName != null) {
            create.put("run_name", runName);
        }
        create.put("tags", runTags);
        Map<String, Object> created = post("/api/2.0/mlflow/runs/create", create);
        Map<String, Object> run = Json.asObject(created.get("run"));
        Map<String, Object> info = run == null ? null : Json.asObject(run.get("info"));
        String runId = info == null ? null : Json.string(info.get("run_id"));
        if (runId == null) {
            throw new IOException("MLflow did not return a run id");
        }

        List<Object> metricList = new ArrayList<>();
        metrics.forEach((key, value) -> {
            if (Double.isFinite(value)) {
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("key", metricKey(key));
                metric.put("value", value);
                metric.put("timestamp", now);
                metric.put("step", 0);
                metricList.add(metric);
            }
        });
        List<Object> paramList = new ArrayList<>();
        params.forEach((key, value) -> paramList.add(tag(metricKey(key), Text.abbreviate(value, 250))));
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("run_id", runId);
        batch.put("metrics", metricList);
        batch.put("params", paramList);
        post("/api/2.0/mlflow/runs/log-batch", batch);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("run_id", runId);
        update.put("status", "FINISHED");
        update.put("end_time", System.currentTimeMillis());
        post("/api/2.0/mlflow/runs/update", update);
        return runId;
    }

    /** MLflow keys may contain letters, digits, _ - . space : and /. */
    public static String metricKey(String name) {
        return name.replace("@", "_at_").replaceAll("[^A-Za-z0-9_\\-. :/]", "_");
    }

    private String experimentId(String name) throws IOException, InterruptedException {
        String path = "/api/2.0/mlflow/experiments/get-by-name?experiment_name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(request(path).GET().build());
        if (response.statusCode() == 200) {
            Map<String, Object> experiment = Json.asObject(parse(response).get("experiment"));
            String id = experiment == null ? null : Json.string(experiment.get("experiment_id"));
            if (id != null) {
                return id;
            }
        } else if (response.statusCode() != 404) {
            throw failure("GET experiments/get-by-name", response);
        }
        Map<String, Object> created = post("/api/2.0/mlflow/experiments/create", Map.of("name", name));
        String id = Json.string(created.get("experiment_id"));
        if (id == null) {
            throw new IOException("MLflow did not return an experiment id");
        }
        return id;
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            throw failure("POST " + path, response);
        }
        return parse(response);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> parse(HttpResponse<String> response) throws IOException {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return Json.parseObject(body);
        } catch (JsonException e) {
            throw new IOException("MLflow returned something that is not JSON: " + Text.abbreviate(body, 120), e);
        }
    }

    private static IOException failure(String call, HttpResponse<String> response) {
        return new IOException("MLflow " + call + " returned HTTP " + response.statusCode() + ": "
                + Text.abbreviate(response.body(), 200));
    }

    private static Map<String, Object> tag(String key, String value) {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("key", key);
        tag.put("value", value);
        return tag;
    }
}
