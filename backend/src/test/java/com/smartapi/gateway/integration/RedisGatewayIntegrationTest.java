package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartapi.gateway.redis.RegistryChangeBroadcaster;
import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.RedisTestSupport;
import com.smartapi.gateway.support.StubBackend;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Gateway + real PostgreSQL + real Redis: shared rate limits, shared response cache, pub/sub invalidation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.store=REDIS",
        "gateway.rate-limit.algorithm=SLIDING_WINDOW",
        "gateway.rate-limit.limit=5",
        "gateway.rate-limit.window=10s",
        "gateway.rate-limit.key-by=USER",
        "gateway.cache.enabled=true",
        "gateway.discovery.cache-ttl=10m"})
class RedisGatewayIntegrationTest extends GatewayTestSupport {

    @BeforeAll
    static void requireRedis() {
        assumeTrue(RedisTestSupport.redisUrl().isPresent(), "No Redis available");
    }

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> RedisTestSupport.redisUrl().orElse("redis://127.0.0.1:1"));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    private final List<StubBackend> backends = new ArrayList<>();

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
        serviceDiscovery.invalidateAll();
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
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

    @Test
    void rateLimitStateLivesInRedis() throws Exception {
        StubBackend a = backend("A");
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);

        for (int i = 0; i < 5; i++) {
            assertThat(get("/gateway/orders/orders", "X-User-Id", "alice").statusCode()).isEqualTo(200);
        }
        assertThat(get("/gateway/orders/orders", "X-User-Id", "alice").statusCode()).isEqualTo(429);
        assertThat(redis.hasKey("rl:sliding_window:user:alice")).isTrue();
        assertThat(redis.opsForZSet().size("rl:sliding_window:user:alice")).isEqualTo(5);
    }

    @Test
    void cacheableGetIsServedFromRedisWithoutCallingTheBackend() throws Exception {
        StubBackend a = backend("A");
        a.cacheControl("public, max-age=60");
        registerInstance(registerService("restaurant-service", "/restaurants", "ROUND_ROBIN"), a.port(), 1);

        HttpResponse<String> first = get("/gateway/restaurants/restaurants/1?lang=en");
        HttpResponse<String> second = get("/gateway/restaurants/restaurants/1?lang=en");

        assertThat(first.headers().firstValue("X-Cache")).hasValue("MISS");
        assertThat(second.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(second.headers().firstValue("Age")).isPresent();
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(a.hits()).isEqualTo(1);                                  // the backend was called once
        Long ttl = redis.getExpire("rc:/restaurants/restaurants/1?lang=en");
        assertThat(ttl).isBetween(55L, 60L);                                // Redis expires it itself

        get("/gateway/restaurants/restaurants/1?lang=fr");                   // different query = different key
        assertThat(a.hits()).isEqualTo(2);
    }

    @Test
    void nonCacheableResponsesAndPersonalisedRequestsBypassTheCache() throws Exception {
        StubBackend a = backend("A");
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);
        get("/gateway/orders/orders", "X-User-Id", "u1");
        get("/gateway/orders/orders", "X-User-Id", "u2");
        assertThat(a.hits()).isEqualTo(2);                                  // no Cache-Control -> never stored

        a.cacheControl("max-age=60");
        String token = "Bearer " + adminToken();                           // a real, valid token
        assertThat(get("/gateway/orders/orders", "Authorization", token).statusCode()).isEqualTo(200);
        assertThat(get("/gateway/orders/orders", "Authorization", token).statusCode()).isEqualTo(200);
        assertThat(a.hits()).isEqualTo(4);                                  // Authorization -> not shared
        // An invalid token is rejected by the gateway before routing (Phase 4 security).
        assertThat(get("/gateway/orders/orders", "Authorization", "Bearer not-a-jwt").statusCode()).isEqualTo(401);
        assertThat(a.hits()).isEqualTo(4);
    }

    @Test
    void registryChangeOnAnotherGatewayInvalidatesThisGatewaysDiscoveryCache() throws Exception {
        StubBackend a = backend("A");
        long instance = registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), a.port(), 1);
        assertThat(get("/gateway/orders/orders", "X-User-Id", "p1").statusCode()).isEqualTo(200); // route cached (TTL 10 min)

        // Another gateway marks the instance DOWN: the row changes in the shared database (not via this
        // gateway, so no local event) and that gateway publishes on the Redis channel.
        jdbcTemplate.update("UPDATE service_instances SET status = 'DOWN' WHERE id = ?", instance);
        redis.convertAndSend(RegistryChangeBroadcaster.CHANNEL, "other-gateway|instance " + instance + " status: DOWN");

        int status = 0;
        for (int i = 0; i < 40 && status != 503; i++) {                    // pub/sub delivery is asynchronous
            Thread.sleep(50);
            status = get("/gateway/orders/orders", "X-User-Id", "p" + (i + 2)).statusCode();
        }
        assertThat(status).isEqualTo(503);                                  // without the broadcast: 10 more minutes of 200
    }
}
