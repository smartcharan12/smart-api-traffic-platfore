package com.smartapi.gateway.ratelimit;

import com.smartapi.gateway.redis.RedisGuard;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared (Redis) limits normally; local in-memory limits while Redis is unavailable.
 *
 * <p>Design choice, fail "soft": when Redis is down each gateway still enforces the limit on its own
 * traffic (so N gateways allow up to N x limit) instead of either rejecting everything (fail closed:
 * a Redis outage becomes a full outage) or allowing everything (fail open: no protection at all).
 */
public class FallbackRateLimiter implements RateLimiter {

    private final RateLimiter shared;
    private final RateLimiter local;
    private final RedisGuard guard;
    private final AtomicLong fallbackDecisions = new AtomicLong();

    public FallbackRateLimiter(RateLimiter shared, RateLimiter local, RedisGuard guard) {
        this.shared = shared;
        this.local = local;
        this.guard = guard;
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return guard.call(() -> shared.tryAcquire(key)).orElseGet(() -> {
            fallbackDecisions.incrementAndGet();
            return local.tryAcquire(key);
        });
    }

    @Override
    public void evictExpired() {
        local.evictExpired(); // Redis keys expire by TTL
    }

    @Override
    public int trackedKeys() {
        return local.trackedKeys();
    }

    public long fallbackDecisions() {
        return fallbackDecisions.get();
    }
}
