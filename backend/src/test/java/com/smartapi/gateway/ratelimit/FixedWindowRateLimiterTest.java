package com.smartapi.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = MutableClock.atNoon();
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(100, Duration.ofMinutes(1), clock);

    @Test
    void walksThroughRequests1To101InOneWindow() {
        RateLimitDecision first = limiter.tryAcquire("alice");
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(99);           // request 1: counter 0 -> 1

        RateLimitDecision fiftieth = null;
        for (int i = 2; i <= 50; i++) {
            fiftieth = limiter.tryAcquire("alice");
        }
        assertThat(fiftieth.remaining()).isEqualTo(50);        // request 50: counter 50

        RateLimitDecision ninetyNinth = null;
        for (int i = 51; i <= 99; i++) {
            ninetyNinth = limiter.tryAcquire("alice");
        }
        assertThat(ninetyNinth.remaining()).isEqualTo(1);      // request 99: one left

        RateLimitDecision hundredth = limiter.tryAcquire("alice");
        assertThat(hundredth.allowed()).isTrue();
        assertThat(hundredth.remaining()).isZero();            // request 100: the last allowed one

        clock.advance(Duration.ofSeconds(20));
        RateLimitDecision hundredFirst = limiter.tryAcquire("alice");
        assertThat(hundredFirst.allowed()).isFalse();          // request 101: rejected
        assertThat(hundredFirst.retryAfter()).isEqualTo(Duration.ofSeconds(40)); // window ends at 12:01:00
    }

    @Test
    void counterResetsWhenTheNextWindowStarts() {
        exhaust("alice");
        clock.advance(Duration.ofSeconds(59).plusMillis(999));
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse(); // 12:00:59.999, same window

        clock.advance(Duration.ofMillis(1));
        RateLimitDecision decision = limiter.tryAcquire("alice");    // 12:01:00.000, new window
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(99);
    }

    @Test
    void allowsTwiceTheLimitAcrossAWindowBoundary() {
        // The known weakness: 100 requests at 12:00:59.9 and 100 more at 12:01:00.0 are all allowed,
        // i.e. 200 requests within 100 ms although the limit is "100 per minute".
        clock.advance(Duration.ofMillis(59_900));
        assertThat(countAllowed("alice", 100)).isEqualTo(100);
        clock.advance(Duration.ofMillis(100));
        assertThat(countAllowed("alice", 100)).isEqualTo(100);
    }

    @Test
    void differentKeysHaveIndependentCounters() {
        exhaust("alice");
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
    }

    @Test
    void rejectedRequestsDoNotExtendThePenalty() {
        exhaust("alice");
        countAllowed("alice", 500); // hammering while blocked...
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("alice").remaining()).isEqualTo(99); // ...doesn't eat the next window
    }

    @Test
    void evictionRemovesOnlyExpiredWindows() {
        limiter.tryAcquire("alice");
        clock.advance(Duration.ofMinutes(1));
        limiter.tryAcquire("bob");
        limiter.evictExpired();
        assertThat(limiter.trackedKeys()).isEqualTo(1); // alice's window has ended, bob's has not
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void exhaust(String key) {
        assertThat(countAllowed(key, 100)).isEqualTo(100);
    }

    private int countAllowed(String key, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(key).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}
