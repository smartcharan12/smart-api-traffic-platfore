package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.metrics.GatewayMetrics;
import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.StubBackend;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Generates traffic, then scrapes /actuator/prometheus exactly as Prometheus would.
 * {@code @AutoConfigureObservability}: Spring Boot tests disable metrics exporters unless asked.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.store=LOCAL",
        "gateway.rate-limit.algorithm=FIXED_WINDOW",
        "gateway.rate-limit.limit=20",
        "gateway.rate-limit.key-by=USER",
        "gateway.cache.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1"})
class MetricsIntegrationTest extends GatewayTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    @Autowired
    private GatewayMetrics metrics;

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
    void prometheusEndpointExposesGatewayMetrics() throws Exception {
        StubBackend ok = StubBackend.start("OK");
        StubBackend bad = StubBackend.start("BAD");
        backends.add(ok);
        backends.add(bad);
        bad.mode(StubBackend.Mode.UNAVAILABLE, 0);
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, ok.port(), 1);
        registerInstance(orders, bad.port(), 1);

        for (int i = 0; i < 12; i++) {
            get("/gateway/orders/orders", "X-User-Id", "m1");   // retries away from BAD, then BAD's circuit opens
        }
        for (int i = 0; i < 21; i++) {
            get("/gateway/nothing/x", "X-User-Id", "m2");       // 20 x 404, then 429
        }
        metrics.refreshInstanceGauges();

        String scrape = get("/actuator/prometheus").body();
        assertThat(scrape)
                .contains("gateway_requests_seconds_bucket{application=\"smart-api-gateway\",method=\"GET\",service=\"orders-service\",status=\"200\",le=\"0.01\"}")
                .contains("gateway_requests_seconds_count{application=\"smart-api-gateway\",method=\"GET\",service=\"unresolved\",status=\"404\"} 20")
                .contains("gateway_requests_seconds_count{application=\"smart-api-gateway\",method=\"GET\",service=\"unresolved\",status=\"429\"} 1")
                .contains("gateway_rate_limit_rejections_total{application=\"smart-api-gateway\"} 1.0")
                .contains("gateway_retries_total{application=\"smart-api-gateway\",reason=\"RETRYABLE_STATUS\",service=\"orders-service\"}")
                .contains("gateway_circuit_breaker_transitions_total{application=\"smart-api-gateway\",to=\"OPEN\",upstream=\"127.0.0.1:" + bad.port() + "\"} 1.0")
                .contains("gateway_circuit_breaker_state{application=\"smart-api-gateway\",upstream=\"127.0.0.1:" + bad.port() + "\"} 2.0")
                .contains("gateway_instance_active_connections{application=\"smart-api-gateway\",service=\"orders-service\",upstream=\"127.0.0.1:" + ok.port() + "\"} 0.0")
                .contains("gateway_request_log_dropped_total")
                .contains("gateway_redis_available");
        // "instance" is Prometheus' own target label; ours must not collide with it.
        assertThat(scrape).doesNotContain("instance=\"127.0.0.1");
        // The raw path never becomes a label value.
        assertThat(scrape).doesNotContain("service=\"/nothing\"");
    }
}
