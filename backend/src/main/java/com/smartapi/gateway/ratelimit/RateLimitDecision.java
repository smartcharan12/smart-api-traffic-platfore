package com.smartapi.gateway.ratelimit;

import java.time.Duration;

/**
 * Result of one rate-limit check. Carries enough information for the standard response headers:
 * {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining} and, when rejected, {@code Retry-After}.
 *
 * @param limit      the configured limit, or -1 when rate limiting is disabled
 * @param remaining  requests still allowed right now after this one
 * @param retryAfter when rejected, how long until a request would be allowed; zero when allowed
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, Duration retryAfter) {

    public static RateLimitDecision allow(int limit, int remaining) {
        return new RateLimitDecision(true, limit, remaining, Duration.ZERO);
    }

    public static RateLimitDecision reject(int limit, Duration retryAfter) {
        return new RateLimitDecision(false, limit, 0, retryAfter);
    }

    public static RateLimitDecision unlimited() {
        return new RateLimitDecision(true, -1, -1, Duration.ZERO);
    }

    public boolean isLimited() {
        return limit >= 0;
    }

    /** Retry-After is whole seconds; round up so a client never retries too early. */
    public long retryAfterSeconds() {
        long millis = retryAfter.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
