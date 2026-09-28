package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.support.StubBackend;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The database dies while the gateway is running. Uses its OWN PostgreSQL (not the shared one), so
 * stopping it can't affect other test classes.
 *
 * <p>Expected: management APIs answer 503 (not 500), health reports DOWN, and proxied traffic to a
 * route that is already in the discovery cache keeps flowing. Request logs are dropped and counted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=false",
        "gateway.cache.enabled=false",
        "gateway.discovery.cache-ttl=10m",
        "spring.data.redis.url=redis://127.0.0.1:1",
        "spring.datasource.hikari.connection-timeout=1000"})
class DatabaseOutageIntegrationTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void cleanup() throws Exception {
        try {
            POSTGRES.close();
        } catch (Exception ignored) {
            // already stopped by the test
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void databaseOutageDegradesTheManagementApiButNotCachedRouting() throws Exception {
        try (StubBackend backend = StubBackend.start("A")) {
            String login = call("POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin-test-password\"}", null).body();
            String token = login.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
            String service = call("POST", "/api/services", "{\"name\":\"orders-service\",\"basePath\":\"/orders\"}", token).body();
            long serviceId = Long.parseLong(service.replaceAll(".*\"id\":(\\d+).*", "$1"));
            call("POST", "/api/service-instances", "{\"serviceId\":%d,\"host\":\"127.0.0.1\",\"port\":%d}"
                    .formatted(serviceId, backend.port()), token);
            assertThat(call("GET", "/gateway/orders/orders", null, null).statusCode()).isEqualTo(200); // route now cached

            POSTGRES.close();                                                                       // the database dies

            HttpResponse<String> health = call("GET", "/api/health", null, null);
            assertThat(health.statusCode()).isEqualTo(503);
            assertThat(health.body()).contains("\"database\":\"DOWN\"");
            HttpResponse<String> services = call("GET", "/api/services", null, token);            // token still valid: JWTs need no DB
            assertThat(services.statusCode()).isEqualTo(503);
            assertThat(services.body()).contains("The database is temporarily unavailable");

            HttpResponse<String> proxied = call("GET", "/gateway/orders/orders", null, null);
            assertThat(proxied.statusCode()).isEqualTo(200);                                        // routing keeps working
            assertThat(backend.hits()).isEqualTo(2);
        }
    }
}
