package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.health.HealthCheckService;
import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.StubBackend;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Active health checks against real HTTP stubs. The schedule is pushed out of the way (1 h) and each
 * cycle is triggered explicitly, so the test controls exactly how many probes happen.
 * No Redis here: the leader lock falls back to "check anyway".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.enabled=false",
        "gateway.cache.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1",
        "gateway.health-check.enabled=true",
        "gateway.health-check.interval=1h",
        "gateway.health-check.initial-delay=1h",
        "gateway.health-check.timeout=1s",
        "gateway.health-check.degraded-latency=300ms"})
class HealthCheckIntegrationTest extends GatewayTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    @Autowired
    private HealthCheckService healthCheckService;

    private final List<StubBackend> backends = new ArrayList<>();

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
        serviceDiscovery.invalidateAll();
    }

    @AfterEach
    void stop() {
        backends.forEach(StubBackend::close);
    }

    private StubBackend backend(String name) throws Exception {
        StubBackend backend = StubBackend.start(name);
        backends.add(backend);
        return backend;
    }

    private String statusOf(long instanceId) {
        return jdbcTemplate.queryForObject("SELECT status FROM service_instances WHERE id = ?", String.class, instanceId);
    }

    @Test
    void probesClassifyInstancesAndDownInstancesStopReceivingTraffic() throws Exception {
        StubBackend fast = backend("FAST");
        StubBackend slow = backend("SLOW");
        slow.mode(StubBackend.Mode.SLOW, 600);
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        long fastId = registerInstance(orders, fast.port(), 1);
        long slowId = registerInstance(orders, slow.port(), 1);
        long deadId = registerInstance(orders, StubBackend.closedPort(), 1);

        healthCheckService.runChecks();
        assertThat(statusOf(fastId)).isEqualTo("HEALTHY");
        assertThat(statusOf(slowId)).isEqualTo("HEALTHY");    // one slow probe could be noise (GC, busy host)
        assertThat(statusOf(deadId)).isEqualTo("DEGRADED");   // 1 failure: suspicious, not yet DOWN

        healthCheckService.runChecks();
        assertThat(statusOf(slowId)).isEqualTo("DEGRADED");   // slower than 300 ms twice in a row
        healthCheckService.runChecks();
        assertThat(statusOf(deadId)).isEqualTo("DOWN");       // 3 consecutive failures

        Integer heartbeats = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM service_instances WHERE last_heartbeat_at IS NOT NULL", Integer.class);
        assertThat(heartbeats).isEqualTo(2);                   // only instances that answered

        String report = get("/api/health/instances").body();
        assertThat(report).contains("\"status\":\"DOWN\"").contains("\"lastError\":\"ConnectException\"")
                .contains("\"availabilityPercent\":100.0");
    }

    @Test
    void recoveredInstanceNeedsTwoGoodProbesBeforeItIsHealthyAgain() throws Exception {
        StubBackend flaky = backend("FLAKY");
        flaky.mode(StubBackend.Mode.ERROR, 0);                 // /health returns 500
        long id = registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), flaky.port(), 1);
        for (int i = 0; i < 3; i++) {
            healthCheckService.runChecks();
        }
        assertThat(statusOf(id)).isEqualTo("DOWN");
        assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(503); // no routable instance

        flaky.mode(StubBackend.Mode.OK, 0);
        List<HealthCheckService.StatusChange> first = healthCheckService.runChecks();
        assertThat(first).isEmpty();                            // one good probe: still DOWN
        List<HealthCheckService.StatusChange> second = healthCheckService.runChecks();
        assertThat(second).singleElement().satisfies(c -> {
            assertThat(c.from()).isEqualTo(InstanceStatus.DOWN);
            assertThat(c.to()).isEqualTo(InstanceStatus.HEALTHY);
        });
        assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(200); // routing picked it up immediately
    }

    @Test
    void customHealthPathIsProbed() throws Exception {
        StubBackend a = backend("A");
        String body = send("POST", "/api/services",
                "{\"name\":\"custom\",\"basePath\":\"/custom\",\"healthCheckPath\":\"/actuator/health\"}").body();
        assertThat(body).contains("\"healthCheckPath\":\"/actuator/health\"");
        long id = registerInstance(1, a.port(), 1);
        healthCheckService.runChecks();
        assertThat(statusOf(id)).isEqualTo("HEALTHY");
        assertThat(a.hits()).isEqualTo(1);
    }
}
