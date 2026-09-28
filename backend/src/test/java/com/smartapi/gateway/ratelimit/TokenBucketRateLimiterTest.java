package com.smartapi.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Capacity 100, refill 10 tokens/second, each request costs 1 token. */
class TokenBucketRateLimiterTest {

    private final MutableClock clock = MutableClock.atNoon();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10.0, clock);

    @Test
    void aFullBucketAllowsABurstUpToCapacity() {
        for (int i = 1; i <= 100; i++) {
            assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        }
        RateLimitDecision empty = limiter.tryAcquire("alice");
        assertThat(empty.allowed()).isFalse();
        assertThat(empty.retryAfter()).isEqualTo(Duration.ofMillis(100)); // 1 token at 10/s = 100 ms
    }

    @Test
    void refillsAtTheConfiguredRate() {
        drain();
        clock.advance(Duration.ofMillis(99));
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse(); // 0.99 tokens: not enough

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();  // 1.0 token

        clock.advance(Duration.ofSeconds(1));                        // +10 tokens
        assertThat(countAllowed(20)).isEqualTo(10);
    }

    @Test
    void neverAccumulatesMoreThanCapacity() {
        drain();
        clock.advance(Duration.ofHours(1)); // would be 36,000 tokens without the cap
        assertThat(countAllowed(500)).isEqualTo(100);
    }

    @Test
    void sustainedRateEqualsRefillRate() {
        drain();
        int allowed = 0;
        for (int second = 0; second < 10; second++) {       // 10 seconds of 50 requests/second
            clock.advance(Duration.ofSeconds(1));
            allowed += countAllowed(50);
        }
        assertThat(allowed).isEqualTo(100);                  // 10 s x 10 tokens/s
    }

    @Test
    void forRateMatchesLimitPerWindow() {
        TokenBucketRateLimiter perMinute = TokenBucketRateLimiter.forRate(60, Duration.ofMinutes(1), clock);
        for (int i = 0; i < 60; i++) {
            perMinute.tryAcquire("k");
        }
        assertThat(perMinute.tryAcquire("k").allowed()).isFalse();
        clock.advance(Duration.ofSeconds(1));                // 60 per minute = 1 per second
        assertThat(perMinute.tryAcquire("k").allowed()).isTrue();
    }

    @Test
    void evictionOnlyRemovesBucketsThatHaveRefilledCompletely() {
        drain();                                             // alice: empty
        limiter.tryAcquire("bob");                           // bob: 99 tokens
        clock.advance(Duration.ofMillis(500));               // bob refills to 100, alice has 5
        limiter.evictExpired();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
        assertThat(countAllowed(10)).isEqualTo(5);           // alice's partial state was kept
    }

    private void drain() {
        assertThat(countAllowed(100)).isEqualTo(100);
    }

    private int countAllowed(int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire("alice").allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}
