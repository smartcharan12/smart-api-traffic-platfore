package com.smartapi.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket: each key has a bucket holding up to {@code capacity} tokens. Tokens are added at
 * {@code refillPerSecond}; a request takes one token or is rejected if the bucket is empty.
 * Allows short bursts (up to capacity) while enforcing the long-run average rate.
 *
 * <p>Refill is lazy: there is no background thread adding tokens. On each request we compute how
 * many tokens accumulated since the last refill: {@code min(capacity, tokens + elapsed x rate)}.
 *
 * <p>Data structure: {@code ConcurrentHashMap<key, Bucket(tokens, lastRefill)>}.
 * Time O(1) per request, space O(K). Two numbers per key, regardless of the limit.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private record Bucket(double tokens, long lastRefillMillis) {
    }

    private static final double REQUEST_COST = 1.0;

    private final int capacity;
    private final double refillPerMilli;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param capacity        maximum burst size
     * @param refillPerSecond tokens added per second (the sustained rate)
     */
    public TokenBucketRateLimiter(int capacity, double refillPerSecond, Clock clock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be >= 1 and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerMilli = refillPerSecond / 1000.0;
        this.clock = clock;
    }

    /** Same average rate as "limit per window": capacity = limit, refill = limit / window. */
    public static TokenBucketRateLimiter forRate(int limit, Duration window, Clock clock) {
        return new TokenBucketRateLimiter(limit, limit / (window.toMillis() / 1000.0), clock);
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        long now = clock.millis();
        RateLimitDecision[] decision = new RateLimitDecision[1];

        buckets.compute(key, (k, bucket) -> {
            double tokens = bucket == null ? capacity : refill(bucket, now); // new keys start full
            if (tokens >= REQUEST_COST) {
                tokens -= REQUEST_COST;
                decision[0] = RateLimitDecision.allow(capacity, (int) Math.floor(tokens));
            } else {
                long waitMillis = (long) Math.ceil((REQUEST_COST - tokens) / refillPerMilli);
                decision[0] = RateLimitDecision.reject(capacity, Duration.ofMillis(waitMillis));
            }
            // Never move lastRefill backwards: a thread that read the clock earlier may run compute()
            // after one that read it later; storing its older time would credit that interval twice.
            long refilledUpTo = bucket == null ? now : Math.max(bucket.lastRefillMillis(), now);
            return new Bucket(tokens, refilledUpTo);
        });
        return decision[0];
    }

    private double refill(Bucket bucket, long now) {
        long elapsed = Math.max(0, now - bucket.lastRefillMillis()); // guard against the clock going backwards
        return Math.min(capacity, bucket.tokens() + elapsed * refillPerMilli);
    }

    /**
     * A bucket that has refilled to capacity is identical to a brand-new bucket, so removing it loses
     * nothing: the next request recreates it full.
     */
    @Override
    public void evictExpired() {
        long now = clock.millis();
        for (String key : buckets.keySet()) {
            buckets.computeIfPresent(key, (k, b) -> refill(b, now) >= capacity ? null : b);
        }
    }

    @Override
    public int trackedKeys() {
        return buckets.size();
    }
}
