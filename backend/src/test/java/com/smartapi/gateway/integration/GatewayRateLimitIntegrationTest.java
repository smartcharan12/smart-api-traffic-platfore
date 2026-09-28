package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.StubBackend;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** The Demo 3 configuration: 5 requests per 10 seconds, one bucket per user. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=true",
        "gateway.cache.enabled=false",
        "gateway.rate-limit.store=LOCAL",
        "spring.data.redis.url=redis://127.0.0.1:1",
        "gateway.rate-limit.algorithm=SLIDING_WINDOW",
        "gateway.rate-limit.limit=5",
        "gateway.rate-limit.window=10s",
        "gateway.rate-limit.key-by=USER"})
class GatewayRateLimitIntegrationTest extends GatewayTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    private StubBackend backend;

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
        serviceDiscovery.invalidateAll(); // TRUNCATE bypasses the application, so no change event fires
        backend = StubBackend.start("A");
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void sixthRequestWithinTheWindowIsRejectedWith429() throws Exception {
        for (int i = 1; i <= 5; i++) {
            HttpResponse<String> ok = get("/gateway/orders/orders", "X-User-Id", "alice");
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.headers().firstValue("X-RateLimit-Limit")).hasValue("5");
            assertThat(ok.headers().firstValue("X-RateLimit-Remaining")).hasValue(String.valueOf(5 - i));
        }

        HttpResponse<String> rejected = get("/gateway/orders/orders", "X-User-Id", "alice");

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Retry-After")).isPresent();
        assertThat(Integer.parseInt(rejected.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 10);
        assertThat(rejected.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(rejected.body()).contains("Rate limit of 5 requests exceeded");
        assertThat(backend.hits()).isEqualTo(5); // the rejected request never reached the backend
    }

    @Test
    void eachUserHasTheirOwnBudget() throws Exception {
        for (int i = 0; i < 5; i++) {
            get("/gateway/orders/orders", "X-User-Id", "carol");
        }
        assertThat(get("/gateway/orders/orders", "X-User-Id", "carol").statusCode()).isEqualTo(429);
        assertThat(get("/gateway/orders/orders", "X-User-Id", "dave").statusCode()).isEqualTo(200);
    }

    @Test
    void rateLimitIsCheckedBeforeTheServiceLookup() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(get("/gateway/unknown/x", "X-User-Id", "erin").statusCode()).isEqualTo(404);
        }
        // Even requests for routes that don't exist consume the budget, and are then rejected
        // without touching the database.
        assertThat(get("/gateway/unknown/x", "X-User-Id", "erin").statusCode()).isEqualTo(429);
    }
}
