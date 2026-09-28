package com.smartapi.gateway.ratelimit;

/**
 * Decides whether one more request for a given key (a user, an IP, an endpoint, or a combination)
 * is allowed right now. The gateway depends only on this interface; which algorithm runs behind it
 * is a configuration choice (see RateLimiterConfig).
 *
 * <p>Implementations must be thread-safe: many request threads call {@link #tryAcquire} concurrently,
 * often for the same key.
 */
public interface RateLimiter {

    /** Records the attempt and returns whether it is allowed. Must be atomic per key. */
    RateLimitDecision tryAcquire(String key);

    /**
     * Drops state for keys that are idle long enough that forgetting them changes nothing. Without
     * this, every IP that ever sent one request would stay in memory forever.
     */
    default void evictExpired() {
    }

    /** Number of keys currently tracked (for tests and monitoring). */
    default int trackedKeys() {
        return 0;
    }
}
