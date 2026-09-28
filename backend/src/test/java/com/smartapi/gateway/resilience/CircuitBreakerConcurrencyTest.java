package com.smartapi.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Concurrent failures and trial calls must not corrupt the state machine. */
class CircuitBreakerConcurrencyTest {

    @Test
    void manyConcurrentFailuresOpenTheCircuitExactlyOnce() throws Exception {
        List<CircuitBreaker.Transition> transitions = new CopyOnWriteArrayList<>();
        CircuitBreaker breaker = new CircuitBreaker("b", new CircuitBreakerConfig(100, 20, 50,
                Duration.ofMinutes(1), 1), MutableClock.atNoon(), transitions::add);
        runConcurrently(1000, () -> {
            if (breaker.tryAcquirePermission()) {
                breaker.onFailure();
            }
        });
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(transitions).hasSize(1);
        assertThat(breaker.snapshot().timesOpened()).isEqualTo(1);
    }

    @Test
    void halfOpenNeverAdmitsMoreThanThePermittedTrialCalls() throws Exception {
        MutableClock clock = MutableClock.atNoon();
        CircuitBreaker breaker = new CircuitBreaker("b", new CircuitBreakerConfig(10, 5, 50,
                Duration.ofSeconds(10), 3), clock, t -> { });
        for (int i = 0; i < 5; i++) {
            breaker.tryAcquirePermission();
            breaker.onFailure();
        }
        clock.advance(Duration.ofSeconds(10));
        AtomicInteger admitted = new AtomicInteger();
        runConcurrently(1000, () -> {
            if (breaker.tryAcquirePermission()) {
                admitted.incrementAndGet(); // held, never completed: slots must not be handed out twice
            }
        });
        assertThat(admitted.get()).isEqualTo(3);
    }

    private static void runConcurrently(int tasks, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
