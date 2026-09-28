package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.logging.RequestLogWriter;
import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.StubBackend;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Gateway traffic ends up in request_logs, and the log/stats APIs read it back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.store=LOCAL",
        "gateway.rate-limit.algorithm=FIXED_WINDOW",
        "gateway.rate-limit.limit=6",
        "gateway.rate-limit.key-by=USER",
        "gateway.cache.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1"})
class RequestLogIntegrationTest extends GatewayTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    @Autowired
    private RequestLogWriter writer;

    private final List<StubBackend> backends = new ArrayList<>();

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE request_logs, service_instances, services RESTART IDENTITY CASCADE");
        serviceDiscovery.invalidateAll();
    }

    @AfterEach
    void stop() {
        backends.forEach(StubBackend::close);
    }

    @Test
    void everyGatewayRequestIsPersistedWithItsOutcome() throws Exception {
        StubBackend ok = StubBackend.start("OK");
        StubBackend flaky = StubBackend.start("FLAKY");
        backends.add(ok);
        backends.add(flaky);
        flaky.mode(StubBackend.Mode.UNAVAILABLE, 0);
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, ok.port(), 1);
        registerInstance(orders, flaky.port(), 1);

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            responses.add(get("/gateway/orders/orders", "X-User-Id", "carol"));
        }
        get("/gateway/nope/x", "X-User-Id", "carol");                          // 404
        responses.add(get("/gateway/orders/orders", "X-User-Id", "carol"));    // 6th allowed
        HttpResponse<String> limited = get("/gateway/orders/orders", "X-User-Id", "carol"); // 429
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(writer.awaitIdle(Duration.ofSeconds(5))).isTrue();

        String page = get("/api/request-logs?size=50").body();
        assertThat(page).contains("\"totalElements\":7")
                .contains("\"clientId\":\"carol\"")
                .contains("\"serviceName\":\"orders-service\"")
                .contains("\"statusCode\":429")
                .contains("\"statusCode\":404")
                .contains("RETRYABLE_STATUS@127.0.0.1:" + flaky.port());   // retried away from FLAKY

        String requestId = responses.get(0).headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(get("/api/request-logs/" + requestId).body()).contains(requestId);

        // What the gateway told each client (X-Retry-Count) must match what it persisted. (A retry also
        // advances the round-robin counter, so which requests hit FLAKY first depends on the sequence.)
        int retriedRequests = 0;
        int totalRetries = 0;
        for (HttpResponse<String> r : responses) {
            assertThat(r.statusCode()).isEqualTo(200);
            int retries = Integer.parseInt(r.headers().firstValue("X-Retry-Count").orElseThrow());
            totalRetries += retries;
            retriedRequests += retries > 0 ? 1 : 0;
        }
        assertThat(totalRetries).isPositive();
        Integer retriedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_logs WHERE retry_count > 0", Integer.class);
        assertThat(retriedRows).isEqualTo(retriedRequests);

        String summary = get("/api/traffic/summary?minutes=5").body();
        assertThat(summary).contains("\"total\":7").contains("\"rate_limited\":1")
                .contains("\"retries\":" + totalRetries)
                .contains("\"p95_ms\"").contains("\"perMinute\"").contains("\"byService\"");

        // Every minute of the window is present, with zeros for quiet minutes (no gaps in charts).
        com.fasterxml.jackson.databind.JsonNode perMinute = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(summary).get("perMinute");
        assertThat(perMinute.size()).isBetween(5, 6);           // a 5-minute window spans 5 or 6 minute buckets
        int sum = 0;
        for (com.fasterxml.jackson.databind.JsonNode m : perMinute) {
            sum += m.get("total").asInt();
        }
        assertThat(sum).isEqualTo(7);

        String clientErrors = get("/api/request-logs?statusClass=4").body();
        assertThat(clientErrors).contains("\"totalElements\":2");            // the 404 and the 429
    }
}
