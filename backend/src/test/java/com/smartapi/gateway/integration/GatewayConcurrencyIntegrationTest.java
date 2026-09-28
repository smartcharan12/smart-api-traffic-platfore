package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.loadbalancer.ActiveConnectionTracker;
import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.StubBackend;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrent traffic through the real gateway. Separate from GatewayIntegrationTest, whose 500 ms
 * backend timeout (chosen for the timeout tests) can legitimately expire under a loaded test JVM:
 * timeouts are not what this test is about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=false",
        "gateway.cache.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1"})
class GatewayConcurrencyIntegrationTest extends GatewayTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    @Autowired
    private ActiveConnectionTracker connectionTracker;

    private final List<StubBackend> backends = new ArrayList<>();

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void cleanRegistry() {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
        serviceDiscovery.invalidateAll();
    }

    @AfterEach
    void stopBackends() {
        backends.forEach(StubBackend::close);
    }

    private StubBackend backend(String name) throws Exception {
        StubBackend backend = StubBackend.start(name);
        backends.add(backend);
        return backend;
    }

    @Test
    void concurrentRequestsAreAllServedAndConnectionCountsReturnToZero() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        StubBackend c = backend("C");
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        for (StubBackend backend : List.of(a, b, c)) {
            registerInstance(orders, backend.port(), 1);
        }

        ExecutorService pool = Executors.newFixedThreadPool(30);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        int retries = 0;
        try {
            for (int i = 0; i < 300; i++) {
                results.add(pool.submit(() -> get("/gateway/orders/orders")));
            }
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> response = result.get(30, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                retries += Integer.parseInt(response.headers().firstValue("X-Retry-Count").orElse("0"));
            }
        } finally {
            pool.shutdownNow();
        }

        // Every backend call is accounted for: one per request plus one per retry the gateway reported.
        assertThat(a.hits() + b.hits() + c.hits()).isEqualTo(300 + retries);
        if (retries == 0) {
            // Round robin hands out tickets atomically, so concurrent callers split exactly. (A retry,
            // e.g. after a timeout on a loaded CI machine, legitimately shifts the split; exactness under
            // contention is also proven deterministically in LoadBalancerConcurrencyTest.)
            assertThat(List.of(a.hits(), b.hits(), c.hits())).containsExactly(100, 100, 100);
        }
        // Every acquire was matched by a release: no leaked "active connections".
        assertThat(connectionTracker.snapshot().values()).allSatisfy(count -> assertThat(count).isZero());
    }
}
