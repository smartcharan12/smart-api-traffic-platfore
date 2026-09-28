package com.smartapi.gateway.ratelimit;

/**
 * Used when rate limiting is disabled (Null Object pattern): the gateway still calls
 * {@code rateLimiter.tryAcquire()} and needs no "if enabled" branches.
 */
public class NoOpRateLimiter implements RateLimiter {

    @Override
    public RateLimitDecision tryAcquire(String key) {
        return RateLimitDecision.unlimited();
    }
}
