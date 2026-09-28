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
 * End to end: client -> gateway (real HTTP, random port) -> registry in real PostgreSQL -> real HTTP
 * stub backends. Rate limiting is off here (tested separately) so it cannot interfere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.enabled=false",
        "gateway.proxy.connect-timeout=500ms",
        "gateway.proxy.response-timeout=500ms"})
class GatewayIntegrationTest extends GatewayTestSupport {

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
        serviceDiscovery.invalidateAll(); // TRUNCATE bypasses the application, so no change event fires
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
    void forwardsRequestAndReturnsBackendResponse() throws Exception {
        StubBackend a = backend("A");
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, a.port(), 1);

        HttpResponse<String> response = get("/gateway/orders/orders/123?expand=items&page=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"instance\":\"A\"")
                .contains("\"method\":\"GET\"")
                .contains("\"path\":\"/orders/123\"")          // service prefix stripped
                .contains("\"query\":\"expand=items&page=2\"") // query string preserved
                .contains("\"forwardedFor\":\"127.0.0.1\"");
        assertThat(response.headers().firstValue("X-Backend")).hasValue("A"); // backend headers passed through
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void generatesRequestIdAndSendsTheSameIdToBackendAndClient() throws Exception {
        StubBackend a = backend("A");
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);

        HttpResponse<String> generated = get("/gateway/orders/orders");
        String id = generated.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(a.lastRequestId()).isEqualTo(id);

        HttpResponse<String> propagated = get("/gateway/orders/orders", "X-Request-ID", "client-trace-0001");
        assertThat(propagated.headers().firstValue("X-Request-ID")).hasValue("client-trace-0001");
        assertThat(a.lastRequestId()).isEqualTo("client-trace-0001");

        HttpResponse<String> unsafe = get("/gateway/orders/orders", "X-Request-ID", "bad id with spaces");
        assertThat(unsafe.headers().firstValue("X-Request-ID").orElseThrow()).isNotEqualTo("bad id with spaces");
    }

    @Test
    void forwardsMethodAndBody() throws Exception {
        StubBackend a = backend("A");
        registerInstance(registerService("payments-service", "/payments", "ROUND_ROBIN"), a.port(), 1);

        HttpResponse<String> response = send("POST", "/gateway/payments/payments", "{\"orderId\":1}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"method\":\"POST\"").contains("\"body\":\"{'orderId':1}\"");
    }

    @Test
    void backendErrorIsPassedThroughUnchanged() throws Exception {
        StubBackend a = backend("A");
        a.mode(StubBackend.Mode.ERROR, 0);
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);

        HttpResponse<String> response = get("/gateway/orders/orders");

        assertThat(response.statusCode()).isEqualTo(500);                   // not 502: the backend did answer
        assertThat(response.body()).contains("\"instance\":\"A\"");        // its own body, not a gateway error
    }

    @Test
    void unknownServiceReturns404() throws Exception {
        HttpResponse<String> response = get("/gateway/nothing-here/x");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No service is registered for path '/nothing-here'")
                .contains("\"requestId\"");
    }

    @Test
    void gatewayUrlWithoutServiceReturns400() throws Exception {
        assertThat(get("/gateway/").statusCode()).isEqualTo(400);
    }

    @Test
    void invalidUserHeaderReturns400() throws Exception {
        registerService("orders-service", "/orders", "ROUND_ROBIN");
        assertThat(get("/gateway/orders/orders", "X-User-Id", "a b").statusCode()).isEqualTo(400);
    }

    @Test
    void serviceWithoutInstancesReturns503() throws Exception {
        registerService("orders-service", "/orders", "ROUND_ROBIN");
        HttpResponse<String> response = get("/gateway/orders/orders");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("has no registered instances");
    }

    @Test
    void downInstanceNeverReceivesTraffic() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, a.port(), 1);
        long bId = registerInstance(orders, b.port(), 1);
        markStatus(bId, "DOWN");

        for (int i = 0; i < 10; i++) {
            assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(200);
        }
        assertThat(a.hits()).isEqualTo(10);
        assertThat(b.hits()).isZero();

        markStatus(bId, "HEALTHY"); // back in rotation immediately
        for (int i = 0; i < 10; i++) {
            get("/gateway/orders/orders");
        }
        assertThat(b.hits()).isEqualTo(5);
    }

    @Test
    void allInstancesDownReturns503() throws Exception {
        StubBackend a = backend("A");
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        markStatus(registerInstance(orders, a.port(), 1), "DOWN");

        HttpResponse<String> response = get("/gateway/orders/orders");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("All 1 instances of service 'orders-service' are DOWN");
        assertThat(a.hits()).isZero();
    }

    @Test
    void refusedConnectionFailsOverToAnotherInstance() throws Exception {
        StubBackend b = backend("B");
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, StubBackend.closedPort(), 1); // registered, but the process is gone
        registerInstance(orders, b.port(), 1);

        for (int i = 0; i < 10; i++) {
            assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(200);
        }
        assertThat(b.hits()).isEqualTo(10);
    }

    @Test
    void allInstancesRefusingConnectionsReturns503() throws Exception {
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, StubBackend.closedPort(), 1);
        registerInstance(orders, StubBackend.closedPort(), 1);

        HttpResponse<String> response = get("/gateway/orders/orders");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Could not connect to any instance of 'orders-service' (2 attempted)");
    }

    @Test
    void slowBackendReturns504AfterResponseTimeout() throws Exception {
        StubBackend a = backend("A");
        a.mode(StubBackend.Mode.SLOW, 3000);
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);

        long start = System.nanoTime();
        HttpResponse<String> response = get("/gateway/orders/orders");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(elapsedMs).isLessThan(2500); // gave up at ~500 ms, didn't wait the full 3 s
        assertThat(a.hits()).isEqualTo(1);      // a timeout is not retried on another instance
    }

    @Test
    void roundRobinSpreadsRequestsEvenly() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        StubBackend c = backend("C");
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        for (StubBackend backend : List.of(a, b, c)) {
            registerInstance(orders, backend.port(), 1);
        }
        for (int i = 0; i < 30; i++) {
            get("/gateway/orders/orders");
        }
        assertThat(List.of(a.hits(), b.hits(), c.hits())).containsExactly(10, 10, 10);
    }

    @Test
    void weightedRoundRobinFollowsWeights() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        StubBackend c = backend("C");
        long orders = registerService("orders-service", "/orders", "WEIGHTED_ROUND_ROBIN");
        registerInstance(orders, a.port(), 3);
        registerInstance(orders, b.port(), 2);
        registerInstance(orders, c.port(), 1);
        for (int i = 0; i < 60; i++) {
            get("/gateway/orders/orders");
        }
        assertThat(List.of(a.hits(), b.hits(), c.hits())).containsExactly(30, 20, 10);
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
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 300; i++) {
                results.add(pool.submit(() -> get("/gateway/orders/orders").statusCode()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        // Round robin hands out tickets atomically, so even 30 concurrent callers split exactly.
        assertThat(List.of(a.hits(), b.hits(), c.hits())).containsExactly(100, 100, 100);
        // Every acquire was matched by a release: no leaked "active connections".
        assertThat(connectionTracker.snapshot().values()).allSatisfy(count -> assertThat(count).isZero());
    }
}
