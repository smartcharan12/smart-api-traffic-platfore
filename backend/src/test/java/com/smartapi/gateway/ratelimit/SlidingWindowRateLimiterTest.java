package com.smartapi.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    private final MutableClock clock = MutableClock.atNoon();
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(100, Duration.ofMinutes(1), clock);

    @Test
    void allowsExactlyTheLimitThenRejects() {
        for (int i = 1; i <= 100; i++) {
            RateLimitDecision decision = limiter.tryAcquire("alice");
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(100 - i);
        }
        RateLimitDecision rejected = limiter.tryAcquire("alice");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMinutes(1)); // oldest request leaves at 12:01:00
    }

    @Test
    void aSlotFreesExactlyWhenTheOldestRequestLeavesTheWindow() {
        limiter.tryAcquire("alice");                       // t = 0
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < 99; i++) {
            limiter.tryAcquire("alice");                   // t = 30 s
        }
        clock.advance(Duration.ofSeconds(29).plusMillis(999));
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse(); // t = 59.999 s: first request still inside

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();  // t = 60 s: it left, one slot free
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse(); // only one: the other 99 are from t = 30 s
    }

    @Test
    void doesNotAllowTheBoundaryBurstThatFixedWindowAllows() {
        // Same traffic as FixedWindowRateLimiterTest.allowsTwiceTheLimitAcrossAWindowBoundary.
        clock.advance(Duration.ofMillis(59_900));
        assertThat(countAllowed(100)).isEqualTo(100);
        clock.advance(Duration.ofMillis(100));
        assertThat(countAllowed(100)).isZero(); // the first 100 are still within the last minute
    }

    @Test
    void evictionDropsKeysWithNoRecentRequests() {
        limiter.tryAcquire("alice");
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("bob");
        clock.advance(Duration.ofSeconds(31));
        limiter.evictExpired();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
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
