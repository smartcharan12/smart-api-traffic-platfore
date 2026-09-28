package com.smartapi.gateway.loadbalancer;

import static com.smartapi.gateway.loadbalancer.Instances.instance;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Round robin and weighted round robin must give an EXACT distribution even when 50 threads pick at
 * once (any lost update would skew the counts). The connection tracker must return to exactly zero
 * after every acquire has been matched by a release.
 */
class LoadBalancerConcurrencyTest {

    private static final int THREADS = 50;
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private Map<Long, LongAdder> pickConcurrently(LoadBalancingStrategy strategy, List<BackendInstance> candidates,
                                                  int picks) throws Exception {
        Map<Long, LongAdder> counts = new ConcurrentHashMap<>();
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < picks; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                counts.computeIfAbsent(strategy.choose(1, candidates).id(), id -> new LongAdder()).increment();
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        return counts;
    }

    @Test
    void roundRobinIsExactUnderContention() throws Exception {
        List<BackendInstance> abc = List.of(instance('A', 1), instance('B', 1), instance('C', 1));
        Map<Long, LongAdder> counts = pickConcurrently(new RoundRobinStrategy(), abc, 3000);
        assertThat(counts.values()).allSatisfy(count -> assertThat(count.sum()).isEqualTo(1000));
    }

    @Test
    void weightedRoundRobinIsExactUnderContention() throws Exception {
        List<BackendInstance> abc = List.of(instance('A', 3), instance('B', 2), instance('C', 1));
        Map<Long, LongAdder> counts = pickConcurrently(new WeightedRoundRobinStrategy(), abc, 6000); // 1000 cycles
        assertThat(counts.get(1L).sum()).isEqualTo(3000);
        assertThat(counts.get(2L).sum()).isEqualTo(2000);
        assertThat(counts.get(3L).sum()).isEqualTo(1000);
    }

    @Test
    void connectionCountsReturnToZeroAndNeverGoNegative() throws Exception {
        ActiveConnectionTracker tracker = new ActiveConnectionTracker();
        AtomicInteger maxSeen = new AtomicInteger();
        AtomicInteger negativeSeen = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            long instanceId = i % 3;
            futures.add(pool.submit(() -> {
                tracker.acquire(instanceId);
                try {
                    int now = tracker.activeConnections(instanceId);
                    maxSeen.accumulateAndGet(now, Math::max);
                    if (now < 0) {
                        negativeSeen.incrementAndGet();
                    }
                } finally {
                    tracker.release(instanceId);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        assertThat(tracker.snapshot()).containsOnlyKeys(0L, 1L, 2L).allSatisfy((id, count) -> assertThat(count).isZero());
        assertThat(negativeSeen.get()).isZero();
        assertThat(maxSeen.get()).isBetween(1, THREADS); // can't exceed the number of threads
    }
}
