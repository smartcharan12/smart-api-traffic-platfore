package com.smartapi.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 1000 requests for the same key from 50 threads, released at the same instant by a latch to
 * maximise contention, with time frozen. With a limit of 100, exactly 100 must be allowed: one more
 * means a race let two threads use the same slot (a lost update). Time is frozen, so the expected
 * count is exact and the test cannot be flaky due to timing.
 */
class RateLimiterConcurrencyTest {

    private static final int LIMIT = 100;
    private static final int REQUESTS = 1000;
    private static final int THREADS = 50;

    static Stream<Arguments> limiters() {
        Function<MutableClock, RateLimiter> fixed = c -> new FixedWindowRateLimiter(LIMIT, Duration.ofMinutes(1), c);
        Function<MutableClock, RateLimiter> sliding = c -> new SlidingWindowRateLimiter(LIMIT, Duration.ofMinutes(1), c);
        Function<MutableClock, RateLimiter> bucket = c -> TokenBucketRateLimiter.forRate(LIMIT, Duration.ofMinutes(1), c);
        return Stream.of(Arguments.of("fixed window", fixed), Arguments.of("sliding window", sliding),
                Arguments.of("token bucket", bucket));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void exactlyLimitRequestsAreAllowedUnderContention(String name, Function<MutableClock, RateLimiter> factory)
            throws Exception {
        RateLimiter limiter = factory.apply(MutableClock.atNoon());
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    if (limiter.tryAcquire("same-user").allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS); // rethrows any exception from a worker
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed.get()).isEqualTo(LIMIT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void concurrentRequestsForDifferentKeysAreEachLimitedIndependently(String name,
                                                                        Function<MutableClock, RateLimiter> factory)
            throws Exception {
        RateLimiter limiter = factory.apply(MutableClock.atNoon());
        int users = 10;
        AtomicInteger[] allowedPerUser = new AtomicInteger[users];
        for (int u = 0; u < users; u++) {
            allowedPerUser[u] = new AtomicInteger();
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < users * 200; i++) {
                int user = i % users;
                futures.add(pool.submit(() -> {
                    if (limiter.tryAcquire("user-" + user).allowed()) {
                        allowedPerUser[user].incrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (AtomicInteger count : allowedPerUser) {
            assertThat(count.get()).isEqualTo(LIMIT);
        }
    }
}
