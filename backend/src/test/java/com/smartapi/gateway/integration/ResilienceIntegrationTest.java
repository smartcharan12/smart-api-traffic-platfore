package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.MutableClock;
import com.smartapi.gateway.support.StubBackend;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Circuit breaker and retry engine, end to end over real HTTP. The application Clock is replaced by
 * a MutableClock, so "wait for the 10 s open duration" is clock.advance(10 s): instant and exact.
 * (Breaker config: window 10, minimum 5 calls, 50% threshold, open 10 s, 2 trial calls.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=false",
        "gateway.cache.enabled=false",
        "gateway.rate-limit.store=LOCAL",
        "spring.data.redis.url=redis://127.0.0.1:1",
        "gateway.discovery.cache-ttl=0",
        "gateway.proxy.response-timeout=500ms"})
class ResilienceIntegrationTest extends GatewayTestSupport {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(java.time.Instant.parse("2026-01-01T12:00:00Z"));
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    @Autowired
    private Clock clock;

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

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    private String circuitStateOf(StubBackend backend) throws Exception {
        String body = get("/api/circuit-breakers").body();
        String marker = "\"address\":\"127.0.0.1:" + backend.port() + "\",\"state\":\"";
        int i = body.indexOf(marker);
        return i < 0 ? "NONE" : body.substring(i + marker.length(), body.indexOf('"', i + marker.length()));
    }

    /** Healthy A + failing B, round robin: B's circuit opens and then all traffic goes to A. */
    @Test
    void failingInstanceOpensItsCircuitAndStopsReceivingTraffic() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        b.mode(StubBackend.Mode.ERROR, 0);
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, a.port(), 1);
        registerInstance(orders, b.port(), 1);

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            statuses.add(get("/gateway/orders/orders").statusCode());
        }

        assertThat(b.hits()).isEqualTo(5);                          // minimum calls, 100% failed -> OPEN
        assertThat(circuitStateOf(b)).isEqualTo("OPEN");
        assertThat(circuitStateOf(a)).isEqualTo("CLOSED");
        assertThat(statuses.subList(10, 20)).containsOnly(200);     // after opening, only A is used
        assertThat(statuses).filteredOn(s -> s == 500).hasSize(5);  // B's 500s passed through (not retried)
    }

    @Test
    void openCircuitGoesHalfOpenAfterTimeoutAndClosesWhenTrialCallsSucceed() throws Exception {
        StubBackend b = backend("B");
        b.mode(StubBackend.Mode.ERROR, 0);
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), b.port(), 1);
        for (int i = 0; i < 5; i++) {
            get("/gateway/orders/orders");
        }
        assertThat(circuitStateOf(b)).isEqualTo("OPEN");

        HttpResponse<String> rejected = get("/gateway/orders/orders");
        assertThat(rejected.statusCode()).isEqualTo(503);           // fail fast: B not called
        assertThat(rejected.headers().firstValue("Retry-After")).hasValue("10");
        assertThat(b.hits()).isEqualTo(5);

        b.mode(StubBackend.Mode.OK, 0);                               // B recovers
        mutableClock().advance(Duration.ofSeconds(10));               // open duration elapses
        assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(200); // trial 1 -> HALF_OPEN
        assertThat(circuitStateOf(b)).isEqualTo("HALF_OPEN");
        assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(200); // trial 2 -> CLOSED
        assertThat(circuitStateOf(b)).isEqualTo("CLOSED");

        String transitions = get("/api/circuit-breakers").body();
        assertThat(transitions).containsSubsequence("\"from\":\"HALF_OPEN\",\"to\":\"CLOSED\"",
                "\"from\":\"OPEN\",\"to\":\"HALF_OPEN\"", "\"from\":\"CLOSED\",\"to\":\"OPEN\"");
    }

    @Test
    void failedTrialCallReopensTheCircuit() throws Exception {
        StubBackend b = backend("B");
        b.mode(StubBackend.Mode.ERROR, 0);
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), b.port(), 1);
        for (int i = 0; i < 5; i++) {
            get("/gateway/orders/orders");
        }
        mutableClock().advance(Duration.ofSeconds(10));
        assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(500); // trial call, still failing
        assertThat(circuitStateOf(b)).isEqualTo("OPEN");
        assertThat(get("/gateway/orders/orders").statusCode()).isEqualTo(503); // open again for 10 s
        assertThat(b.hits()).isEqualTo(6);
    }

    // Round-robin position is per service id and ids are reused between tests, so these tests send two
    // requests: whichever order round robin uses, exactly one of them is first sent to A.
    @Test
    void transient503IsRetriedOnAnotherInstanceForGet() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        a.mode(StubBackend.Mode.UNAVAILABLE, 0);
        long orders = registerService("orders-service", "/orders", "ROUND_ROBIN");
        registerInstance(orders, a.port(), 1);
        registerInstance(orders, b.port(), 1);

        List<HttpResponse<String>> responses = List.of(get("/gateway/orders/orders"), get("/gateway/orders/orders"));

        assertThat(responses).allSatisfy(r -> {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("\"instance\":\"B\"");
        });
        assertThat(responses).extracting(r -> r.headers().firstValue("X-Retry-Count").orElseThrow())
                .containsExactlyInAnyOrder("0", "1"); // the request first sent to A was retried on B
        assertThat(a.hits()).isEqualTo(1);
    }

    @Test
    void postIsNotRetriedOn503UnlessItCarriesAnIdempotencyKey() throws Exception {
        StubBackend a = backend("A");
        StubBackend b = backend("B");
        a.mode(StubBackend.Mode.UNAVAILABLE, 0);
        long payments = registerService("payments-service", "/payments", "ROUND_ROBIN");
        registerInstance(payments, a.port(), 1);
        registerInstance(payments, b.port(), 1);

        List<HttpResponse<String>> plain = List.of(send("POST", "/gateway/payments/pay", "{}"),
                send("POST", "/gateway/payments/pay", "{}"));
        assertThat(plain).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 503); // A's 503 passed through
        assertThat(plain).allSatisfy(r -> assertThat(r.headers().firstValue("X-Retry-Count")).hasValue("0"));

        List<HttpResponse<String>> keyed = List.of(
                send("POST", "/gateway/payments/pay", "{}", "Idempotency-Key", "pay-0001"),
                send("POST", "/gateway/payments/pay", "{}", "Idempotency-Key", "pay-0002"));
        assertThat(keyed).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(keyed).extracting(r -> r.headers().firstValue("X-Retry-Count").orElseThrow())
                .containsExactlyInAnyOrder("0", "1");
        assertThat(a.hits()).isEqualTo(2);    // one plain POST, one keyed POST; neither was sent to A twice
    }

    @Test
    void retriesUseExponentialBackoff() throws Exception {
        StubBackend a = backend("A");
        a.mode(StubBackend.Mode.UNAVAILABLE, 0);
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);

        long start = System.nanoTime();
        HttpResponse<String> response = get("/gateway/orders/orders");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("X-Retry-Count")).hasValue("2");
        assertThat(a.hits()).isEqualTo(3);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(240); // waited ~100 ms then ~200 ms (each -20% at worst)
    }
}
