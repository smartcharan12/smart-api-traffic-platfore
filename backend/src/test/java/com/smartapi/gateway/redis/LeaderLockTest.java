package com.smartapi.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartapi.gateway.support.MutableClock;
import com.smartapi.gateway.support.RedisTestSupport;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Two gateways compete for the health-checker lease in a real Redis. */
class LeaderLockTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        String url = RedisTestSupport.redisUrl().orElse(null);
        assumeTrue(url != null, "No Redis available");
        URI uri = URI.create(url);
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void onlyOneGatewayLeadsAndAnotherTakesOverWhenTheLeaseExpires() throws Exception {
        String key = "test:leader:" + System.nanoTime();
        RedisGuard guard = new RedisGuard(MutableClock.atNoon());
        LeaderLock gatewayA = new LeaderLock(redis, guard, key, Duration.ofMillis(300));
        LeaderLock gatewayB = new LeaderLock(redis, guard, key, Duration.ofMillis(300));

        assertThat(gatewayA.acquireOrRenew()).isTrue();
        assertThat(gatewayB.acquireOrRenew()).isFalse();
        assertThat(gatewayA.acquireOrRenew()).isTrue();       // renewal by the holder
        assertThat(redis.opsForValue().get(key)).isEqualTo(gatewayA.id());

        Thread.sleep(500);                                     // A stops renewing (crashed); Redis expires the key
        assertThat(gatewayB.acquireOrRenew()).isTrue();
        assertThat(gatewayA.acquireOrRenew()).isFalse();
    }

    @Test
    void withoutRedisEveryGatewayDoesTheWork() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        try {
            LeaderLock lock = new LeaderLock(new StringRedisTemplate(dead), new RedisGuard(MutableClock.atNoon()),
                    "k", Duration.ofSeconds(1));
            assertThat(lock.acquireOrRenew()).isTrue();
        } finally {
            dead.destroy();
        }
    }
}
