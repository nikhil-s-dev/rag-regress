package io.github.nikhilucumberlands.ragregress.retrievers;

import io.github.nikhilucumberlands.ragregress.Retriever;
import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonPath;
import io.github.nikhilucumberlands.ragregress.util.Text;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls a retrieval service over HTTP: POSTs a JSON body built from a template and reads the
 * document ids out of the response with a {@link JsonPath}.
 *
 * <p>In the template, a string value equal to {@code "{k}"} becomes the number k, and
 * {@code {query}} or {@code {k}} inside any other string is replaced with the query text or k.
 * The default template is {@code {"query": "{query}", "k": "{k}"}}.
 */
public final class HttpRetriever implements Retriever {

    private final URI url;
    private final Object bodyTemplate;
    private final JsonPath idPath;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient client;

    public HttpRetriever(URI url, Object bodyTemplate, JsonPath idPath, Map<String, String> headers, Duration timeout) {
        this.url = url;
        this.bodyTemplate = bodyTemplate == null ? defaultBody() : bodyTemplate;
        this.idPath = idPath;
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static Map<String, Object> defaultBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", "{query}");
        body.put("k", "{k}");
        return body;
    }

    @Override
    public List<String> retrieve(String query, int k) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(fill(bodyTemplate, query, k))));
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + ": " + Text.abbreviate(response.body(), 200));
        }
        Object json = Json.parse(response.body());
        List<String> ids = new ArrayList<>();
        for (Object value : idPath.extract(json)) {
            ids.add(idString(value));
        }
        return ids;
    }

    /** Fills {@code {query}} and {@code {k}} placeholders anywhere in the template. */
    static Object fill(Object template, String query, int k) {
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> filled = new LinkedHashMap<>();
            map.forEach((key, value) -> filled.put(String.valueOf(key), fill(value, query, k)));
            return filled;
        }
        if (template instanceof List<?> list) {
            List<Object> filled = new ArrayList<>();
            list.forEach(value -> filled.add(fill(value, query, k)));
            return filled;
        }
        if (template instanceof String text) {
            if (text.equals("{k}")) {
                return (long) k;
            }
            return text.replace("{query}", query).replace("{k}", String.valueOf(k));
        }
        return template;
    }

    static String idString(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return url + " (ids at " + idPath + ")";
    }
}
