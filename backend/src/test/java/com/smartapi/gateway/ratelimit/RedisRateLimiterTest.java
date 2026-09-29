package com.smartapi.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartapi.gateway.redis.RedisGuard;
import com.smartapi.gateway.support.MutableClock;
import com.smartapi.gateway.support.RedisTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Runs against a real Redis (see RedisTestSupport). No Spring context: just the limiter and a client. */
class RedisRateLimiterTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final MutableClock clock = MutableClock.atNoon();

    @BeforeAll
    static void connect() {
        String url = RedisTestSupport.redisUrl().orElse(null);
        assumeTrue(url != null, "No Redis available (Docker, TEST_REDIS_URL or redis-server binary)");
        java.net.URI uri = java.net.URI.create(url);
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(uri.getHost(), uri.getPort()));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void flush() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    private RedisRateLimiter limiter(RateLimitAlgorithm algorithm) {
        return new RedisRateLimiter(redis, algorithm, 100, Duration.ofMinutes(1), clock);
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void allowsExactlyTheLimitThenRejects(RateLimitAlgorithm algorithm) {
        RedisRateLimiter limiter = limiter(algorithm);
        for (int i = 1; i <= 100; i++) {
            RateLimitDecision d = limiter.tryAcquire("alice");
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(100 - i);
        }
        RateLimitDecision rejected = limiter.tryAcquire("alice");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isPositive();
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();   // keys are independent
    }

    @Test
    void fixedWindowResetsAtTheBoundary() {
        RedisRateLimiter limiter = limiter(RateLimitAlgorithm.FIXED_WINDOW);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("alice");
        }
        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire("alice").retryAfter()).isEqualTo(Duration.ofSeconds(40));
        clock.advance(Duration.ofSeconds(40));
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
    }

    @Test
    void slidingWindowFreesASlotWhenTheOldestRequestLeaves() {
        RedisRateLimiter limiter = limiter(RateLimitAlgorithm.SLIDING_WINDOW);
        limiter.tryAcquire("alice");
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < 99; i++) {
            limiter.tryAcquire("alice");
        }
        clock.advance(Duration.ofMillis(29_999));
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
    }

    @Test
    void tokenBucketRefillsFractionally() {
        RedisRateLimiter limiter = new RedisRateLimiter(redis, RateLimitAlgorithm.TOKEN_BUCKET, 10,
                Duration.ofSeconds(1), clock);                      // 10 tokens, 10 per second
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("alice");
        }
        RateLimitDecision empty = limiter.tryAcquire("alice");
        assertThat(empty.allowed()).isFalse();
        assertThat(empty.retryAfter()).isEqualTo(Duration.ofMillis(100));
        clock.advance(Duration.ofMillis(50));
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse(); // 0.5 token, kept as a fraction
        clock.advance(Duration.ofMillis(50));
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();  // 1.0 token
    }

    /** Same regression as the in-memory bucket: scripts may run out of timestamp order under load. */
    @Test
    void tokenBucketIgnoresOutOfOrderTimestamps() {
        RedisRateLimiter limiter = new RedisRateLimiter(redis, RateLimitAlgorithm.TOKEN_BUCKET, 10,
                Duration.ofSeconds(1), clock);                      // 10 tokens, 10 per second
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("bob");
        }
        clock.advance(Duration.ofMillis(500));                      // 5 tokens
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
        clock.advance(Duration.ofMillis(-300));                     // older timestamp arrives later
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
        clock.advance(Duration.ofMillis(300));
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("bob").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);                           // bug: 6
    }

    /**
     * Why Redis: two gateways, alternating requests from one client. With in-memory limiters each
     * gateway has its own bucket and the client gets TWICE the limit. With Redis they share one bucket.
     */
    @Test
    void twoGatewaysShareOneBudgetOnlyWithRedis() {
        RateLimiter localA = new FixedWindowRateLimiter(100, Duration.ofMinutes(1), clock);
        RateLimiter localB = new FixedWindowRateLimiter(100, Duration.ofMinutes(1), clock);
        RateLimiter redisA = limiter(RateLimitAlgorithm.FIXED_WINDOW);
        RateLimiter redisB = limiter(RateLimitAlgorithm.FIXED_WINDOW);

        assertThat(allowedAlternating(localA, localB, 300)).isEqualTo(200);
        assertThat(allowedAlternating(redisA, redisB, 300)).isEqualTo(100);
    }

    @ParameterizedTest
    @EnumSource(RateLimitAlgorithm.class)
    void thousandConcurrentRequestsFromTwoGatewaysAllowExactlyTheLimit(RateLimitAlgorithm algorithm)
            throws Exception {
        RateLimiter gatewayA = limiter(algorithm);
        RateLimiter gatewayB = limiter(algorithm);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                RateLimiter gateway = i % 2 == 0 ? gatewayA : gatewayB;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (gateway.tryAcquire("same-user").allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void redisOutageFallsBackToTheLocalLimiter() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        try {
            RedisGuard guard = new RedisGuard(clock);
            FallbackRateLimiter limiter = new FallbackRateLimiter(
                    new RedisRateLimiter(new StringRedisTemplate(dead), RateLimitAlgorithm.FIXED_WINDOW, 5,
                            Duration.ofMinutes(1), clock),
                    new FixedWindowRateLimiter(5, Duration.ofMinutes(1), clock), guard);

            int allowed = 0;
            for (int i = 0; i < 10; i++) {
                if (limiter.tryAcquire("alice").allowed()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);                   // still limited, by the local fallback
            assertThat(limiter.fallbackDecisions()).isEqualTo(10);
            assertThat(guard.failures()).isEqualTo(3);          // then the guard stopped trying Redis
            assertThat(guard.skipped()).isEqualTo(7);
        } finally {
            dead.destroy();
        }
    }

    private static int allowedAlternating(RateLimiter a, RateLimiter b, int requests) {
        int allowed = 0;
        for (int i = 0; i < requests; i++) {
            if ((i % 2 == 0 ? a : b).tryAcquire("alice").allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}
