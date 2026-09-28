package com.smartapi.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), 0.2);

    @Test
    void withoutJitterTheBackoffDoubles() {
        assertThat(policy.backoff(1, () -> 0.5)).isEqualTo(Duration.ofMillis(100)); // factor exactly 1.0
        assertThat(policy.backoff(2, () -> 0.5)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoff(3, () -> 0.5)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        assertThat(policy.backoff(10, () -> 0.5)).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void jitterStaysWithinPlusMinusTwentyPercent() {
        assertThat(policy.backoff(2, () -> 0.0)).isEqualTo(Duration.ofMillis(160));
        assertThat(policy.backoff(2, () -> 0.999999)).isEqualTo(Duration.ofMillis(240));
    }

    @Test
    void realRandomJitterSpreadsRetriesOut() {
        java.util.Set<Long> distinct = new java.util.HashSet<>();
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            long ms = policy.backoff(3, random::nextDouble).toMillis();
            assertThat(ms).isBetween(320L, 480L);
            distinct.add(ms);
        }
        assertThat(distinct.size()).isGreaterThan(50); // clients that failed together don't retry together
    }
}
