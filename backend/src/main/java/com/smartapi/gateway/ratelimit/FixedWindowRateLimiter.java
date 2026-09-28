package com.smartapi.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed window counter: time is cut into aligned windows (e.g. 12:00:00-12:00:59, 12:01:00-...),
 * and each key gets {@code limit} requests per window. The counter resets when a new window starts.
 *
 * <p>Data structure: {@code ConcurrentHashMap<key, Window(start, count)>}.
 * Time O(1) per request. Space O(K) for K active keys.
 *
 * <p>Weakness: bursts at a boundary. A client can send {@code limit} requests at 12:00:59.9 and
 * another {@code limit} at 12:01:00.0: twice the limit within 100 ms. The test
 * {@code allowsTwiceTheLimitAcrossAWindowBoundary} demonstrates it.
 */
public class FixedWindowRateLimiter implements RateLimiter {

    /** Immutable, so the map value is replaced (never mutated) inside compute(). */
    private record Window(long startMillis, int count) {
    }

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
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
        long windowStart = now - Math.floorMod(now, windowMillis); // aligned: every key shares boundaries
        RateLimitDecision[] decision = new RateLimitDecision[1];

        // compute() runs the lambda atomically for this key: two threads updating the same key are
        // serialised, so no increment is lost. Different keys proceed in parallel.
        windows.compute(key, (k, current) -> {
            if (current == null || current.startMillis() != windowStart) {
                decision[0] = RateLimitDecision.allow(limit, limit - 1);   // first request of a new window
                return new Window(windowStart, 1);
            }
            if (current.count() < limit) {
                int count = current.count() + 1;
                decision[0] = RateLimitDecision.allow(limit, limit - count);
                return new Window(windowStart, count);
            }
            decision[0] = RateLimitDecision.reject(limit, Duration.ofMillis(windowStart + windowMillis - now));
            return current; // rejected requests don't change state
        });
        return decision[0];
    }

    @Override
    public void evictExpired() {
        long now = clock.millis();
        long currentWindowStart = now - Math.floorMod(now, windowMillis);
        // computeIfPresent is atomic per key, so an entry that a request just updated is re-checked,
        // not blindly removed.
        for (String key : windows.keySet()) {
            windows.computeIfPresent(key, (k, w) -> w.startMillis() < currentWindowStart ? null : w);
        }
    }

    @Override
    public int trackedKeys() {
        return windows.size();
    }
}
