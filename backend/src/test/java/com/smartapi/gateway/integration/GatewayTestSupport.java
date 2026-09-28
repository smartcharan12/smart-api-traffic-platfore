package com.smartapi.gateway.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Talks to the running gateway over real HTTP, exactly like an external client would. */
abstract class GatewayTestSupport extends PostgresIntegrationTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    protected final HttpClient http = HttpClient.newHttpClient();

    protected abstract int port();

    protected HttpResponse<String> send(String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (headers.length > 0) {
            builder.headers(headers);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return send("GET", path, null, headers);
    }

    protected long registerService(String name, String basePath, String strategy) throws Exception {
        HttpResponse<String> response = send("POST", "/api/services", """
                {"name":"%s","basePath":"%s","loadBalancingStrategy":"%s"}""".formatted(name, basePath, strategy));
        return idOf(response);
    }

    protected long registerInstance(long serviceId, int port, int weight) throws Exception {
        HttpResponse<String> response = send("POST", "/api/service-instances", """
                {"serviceId":%d,"host":"127.0.0.1","port":%d,"weight":%d}""".formatted(serviceId, port, weight));
        return idOf(response);
    }

    protected void markStatus(long instanceId, String status) throws Exception {
        HttpResponse<String> response = send("PATCH", "/api/service-instances/" + instanceId + "/status",
                "{\"status\":\"%s\"}".formatted(status));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("PATCH failed: " + response.body());
        }
    }

    private static long idOf(HttpResponse<String> response) {
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Registration failed: " + response.statusCode() + " " + response.body());
        }
        Matcher matcher = ID.matcher(response.body());
        if (!matcher.find()) {
            throw new IllegalStateException("No id in " + response.body());
        }
        return Long.parseLong(matcher.group(1));
    }
}
