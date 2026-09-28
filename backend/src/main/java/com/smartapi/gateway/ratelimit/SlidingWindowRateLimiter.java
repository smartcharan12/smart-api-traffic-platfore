package com.smartapi.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding window log: remember the timestamp of every allowed request in the last {@code window}.
 * A new request is allowed if fewer than {@code limit} timestamps remain after discarding the ones
 * older than {@code now - window}. There is no boundary, so no double burst.
 *
 * <p>Data structure: {@code ConcurrentHashMap<key, Deque<Long>>}. The deque is a FIFO queue ordered by
 * time: new timestamps go on the tail, expired ones leave from the head. Because it is sorted, we
 * only ever look at the head to expire entries.
 *
 * <p>Time: amortised O(1) per request (each timestamp is added once and removed once).
 * Space: O(limit) per key, O(K x limit) total. That memory cost is the price of exactness;
 * the fixed window stores one counter per key.
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    // ArrayDeque is NOT thread-safe. It is only touched inside compute()/computeIfPresent() for its
    // key, which ConcurrentHashMap executes atomically per key, so no two threads ever touch the same
    // deque at the same time.
    private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit must be >= 1 and window must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        long now = clock.millis();
        RateLimitDecision[] decision = new RateLimitDecision[1];

        logs.compute(key, (k, log) -> {
            Deque<Long> timestamps = log != null ? log : new ArrayDeque<>();
            evictOlderThan(timestamps, now - windowMillis);
            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                decision[0] = RateLimitDecision.allow(limit, limit - timestamps.size());
            } else {
                // The oldest request leaves the window at oldest + window; one slot frees up then.
                long retryAfter = timestamps.peekFirst() + windowMillis - now;
                decision[0] = RateLimitDecision.reject(limit, Duration.ofMillis(retryAfter));
            }
            return timestamps;
        });
        return decision[0];
    }

    /** A timestamp t is inside the window while t > now - window. */
    private static void evictOlderThan(Deque<Long> timestamps, long cutoff) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }
    }

    @Override
    public void evictExpired() {
        long cutoff = clock.millis() - windowMillis;
        for (String key : logs.keySet()) {
            logs.computeIfPresent(key, (k, timestamps) -> {
                evictOlderThan(timestamps, cutoff);
                return timestamps.isEmpty() ? null : timestamps; // returning null removes the key
            });
        }
    }

    @Override
    public int trackedKeys() {
        return logs.size();
    }
}
