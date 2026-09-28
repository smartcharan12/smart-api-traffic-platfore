package com.smartapi.gateway.logging;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.entity.RequestLog;
import com.smartapi.gateway.repository.RequestLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Producer-consumer pipeline for request logs.
 *
 * <p>Producers are the request threads: {@link #submit} does a non-blocking {@code offer()} on a bounded
 * {@link ArrayBlockingQueue} and returns immediately, so logging never adds database latency to a
 * request. When the queue is full (database slow or down), the entry is DROPPED and counted: a
 * bounded queue can't exhaust memory, and a request is never delayed to save a log row.
 *
 * <p>The consumer is one background thread: it blocks in {@code poll()} until an entry arrives, drains up
 * to {@code batchSize} more with {@code drainTo()}, and writes them in one transaction. Hibernate
 * batches the INSERTs (jdbc.batch_size), which works because request_logs ids come from a pooled
 * sequence (Phase 1, V1 migration) rather than an IDENTITY column.
 */
@Component
public class RequestLogWriter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    private final RequestLogRepository repository;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int batchSize;
    private final BlockingQueue<RequestLog> queue;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    public RequestLogWriter(RequestLogRepository repository, TransactionTemplate transactions,
                            GatewayProperties properties) {
        this.repository = repository;
        this.transactions = transactions;
        this.enabled = properties.requestLog().enabled();
        this.batchSize = properties.requestLog().batchSize();
        this.queue = new ArrayBlockingQueue<>(properties.requestLog().queueCapacity());
        this.worker = new Thread(this::drainLoop, "request-log-writer");
        this.worker.setDaemon(true);
    }

    @PostConstruct
    void start() {
        if (enabled) {
            worker.start();
        }
    }

    /** Never blocks. @return false if the entry was dropped */
    public boolean submit(RequestLog entry) {
        if (!enabled) {
            return false;
        }
        submitted.incrementAndGet();
        if (queue.offer(entry)) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                RequestLog first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<RequestLog> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void write(List<RequestLog> batch) {
        try {
            transactions.executeWithoutResult(status -> repository.saveAll(batch));
            written.addAndGet(batch.size());
        } catch (RuntimeException e) {
            dropped.addAndGet(batch.size());
            log.warn("Dropped {} request log rows: {}", batch.size(), e.getMessage());
        }
    }

    /** For tests and demos: wait until everything submitted so far is written or dropped. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (written.get() + dropped.get() < submitted.get()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    public long written() {
        return written.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public int queued() {
        return queue.size();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        worker.join(5000); // give it a chance to flush what's queued
    }
}
