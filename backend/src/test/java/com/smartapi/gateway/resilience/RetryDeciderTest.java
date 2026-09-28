package com.smartapi.gateway.resilience;

import static com.smartapi.gateway.resilience.RetryDecider.FailureKind.BAD_GATEWAY;
import static com.smartapi.gateway.resilience.RetryDecider.FailureKind.CONNECT_FAILED;
import static com.smartapi.gateway.resilience.RetryDecider.FailureKind.NON_RETRYABLE_STATUS;
import static com.smartapi.gateway.resilience.RetryDecider.FailureKind.RETRYABLE_STATUS;
import static com.smartapi.gateway.resilience.RetryDecider.FailureKind.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RetryDeciderTest {

    private final RetryDecider decider = new RetryDecider();
    private final HttpHeaders none = new HttpHeaders();

    @Test
    void connectFailuresAreAlwaysRetryableBecauseTheBackendNeverSawTheRequest() {
        assertThat(decider.isRetryable(CONNECT_FAILED, "POST", none)).isTrue();
    }

    @Test
    void transientFailuresAreRetriedOnlyForIdempotentRequests() {
        for (var kind : new RetryDecider.FailureKind[]{TIMEOUT, BAD_GATEWAY, RETRYABLE_STATUS}) {
            assertThat(decider.isRetryable(kind, "GET", none)).isTrue();
            assertThat(decider.isRetryable(kind, "PUT", none)).isTrue();
            assertThat(decider.isRetryable(kind, "POST", none)).isFalse();   // could double-charge
            assertThat(decider.isRetryable(kind, "PATCH", none)).isFalse();
        }
    }

    @Test
    void idempotencyKeyMakesAPostRetryable() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "pay-8f14e45f");
        assertThat(decider.isRetryable(TIMEOUT, "POST", headers)).isTrue();
    }

    @Test
    void permanentFailuresAreNeverRetried() {
        assertThat(RetryDecider.classifyStatus(500)).isEqualTo(NON_RETRYABLE_STATUS);
        assertThat(RetryDecider.classifyStatus(404)).isEqualTo(NON_RETRYABLE_STATUS);
        assertThat(RetryDecider.classifyStatus(503)).isEqualTo(RETRYABLE_STATUS);
        assertThat(decider.isRetryable(NON_RETRYABLE_STATUS, "GET", none)).isFalse();
    }
}
