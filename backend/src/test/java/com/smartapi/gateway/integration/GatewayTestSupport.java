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
    /**
     * One login per running gateway (keyed by port), not per test method: JUnit creates a new test
     * instance per method, and the login endpoint allows only 10 attempts per minute per IP.
     */
    private static final java.util.Map<Integer, String> ADMIN_TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    protected abstract int port();

    /** For tests that move the clock: the cached token may have expired. */
    protected void forgetAdminToken() {
        ADMIN_TOKENS.remove(port());
    }

    /** Management calls (/api/**) are made as the bootstrap ADMIN (see src/test/resources/application.properties). */
    protected String adminToken() throws IOException, InterruptedException {
        String adminToken = ADMIN_TOKENS.get(port());
        if (adminToken == null) {
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin-test-password\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(login.body());
            if (!m.find()) {
                throw new IllegalStateException("Admin login failed: " + login.statusCode() + " " + login.body());
            }
            adminToken = m.group(1);
            ADMIN_TOKENS.put(port(), adminToken);
        }
        return adminToken;
    }

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
        if (path.startsWith("/api/") && !path.startsWith("/api/auth/") && !java.util.Arrays.asList(headers).contains("Authorization")) {
            builder.header("Authorization", "Bearer " + adminToken());
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
