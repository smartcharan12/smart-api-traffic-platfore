package com.smartapi.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.entity.RequestLog;
import com.smartapi.gateway.repository.RequestLogRepository;
import com.smartapi.gateway.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RequestLogWriterTest {

    private final RequestLogRepository repository = mock(RequestLogRepository.class);
    private final TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));

    private RequestLogWriter writer(int capacity, int batchSize) {
        GatewayProperties base = TestProperties.withKeyBy(com.smartapi.gateway.ratelimit.RateLimitDimension.IP);
        GatewayProperties props = new GatewayProperties(base.proxy(), base.rateLimit(), base.discovery(),
                base.circuitBreaker(), base.retry(), base.cache(), base.healthCheck(),
                new GatewayProperties.RequestLog(true, capacity, batchSize, Duration.ofDays(7)));
        return new RequestLogWriter(repository, transactions, props);
    }

    private static RequestLog entry() {
        return new RequestLog(UUID.randomUUID(), null, 1L, 1L, "GET", "/gateway/x", "127.0.0.1", 200, 3, 0, null,
                Instant.now());
    }

    @Test
    void fullQueueDropsInsteadOfBlockingTheRequest() throws Exception {
        RequestLogWriter writer = writer(2, 10);            // worker not started yet: nothing is consumed
        assertThat(writer.submit(entry())).isTrue();
        assertThat(writer.submit(entry())).isTrue();
        long start = System.nanoTime();
        assertThat(writer.submit(entry())).isFalse();       // queue full -> dropped
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.MILLISECONDS.toNanos(50)); // didn't wait
        assertThat(writer.dropped()).isEqualTo(1);

        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        when(repository.saveAll(anyList())).thenAnswer(inv -> {
            batchSizes.add(((List<?>) inv.getArgument(0)).size());
            return inv.getArgument(0);
        });
        writer.start();
        assertThat(writer.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(batchSizes).containsExactly(2);          // both queued rows written in ONE batch
        writer.stop();
    }

    @Test
    void databaseFailureDropsTheBatchAndKeepsGoing() throws Exception {
        when(repository.saveAll(anyList())).thenThrow(new RuntimeException("database down")).thenAnswer(i -> i.getArgument(0));
        RequestLogWriter writer = writer(100, 10);
        writer.start();
        writer.submit(entry());
        assertThat(writer.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(writer.dropped()).isEqualTo(1);
        writer.submit(entry());
        assertThat(writer.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(writer.written()).isEqualTo(1);           // the writer thread survived the failure
        writer.stop();
    }

    @Test
    void thousandConcurrentProducersLoseNothingWhenThereIsRoom() throws Exception {
        when(repository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        RequestLogWriter writer = writer(10_000, 500);
        writer.start();
        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                futures.add(pool.submit(() -> writer.submit(entry())));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(writer.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(writer.written()).isEqualTo(1000);
        assertThat(writer.dropped()).isZero();
        writer.stop();
    }
}
