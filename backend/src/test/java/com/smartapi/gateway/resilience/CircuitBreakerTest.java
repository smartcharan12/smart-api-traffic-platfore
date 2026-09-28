package com.smartapi.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Window 10, minimum 5 calls, open at >= 50% failures, open for 10 s, 2 trial calls. */
class CircuitBreakerTest {

    private final MutableClock clock = MutableClock.atNoon();
    private final List<CircuitBreaker.Transition> transitions = new ArrayList<>();
    private final CircuitBreaker breaker = new CircuitBreaker("orders-9002",
            new CircuitBreakerConfig(10, 5, 50, Duration.ofSeconds(10), 2), clock, transitions::add);

    private void call(boolean success) {
        assertThat(breaker.tryAcquirePermission()).isTrue();
        if (success) {
            breaker.onSuccess();
        } else {
            breaker.onFailure();
        }
    }

    private void openIt() {
        for (int i = 0; i < 5; i++) {
            call(false);
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void staysClosedUntilMinimumCallsAreRecorded() {
        for (int i = 0; i < 4; i++) {
            call(false); // 4 failures out of 4: 100%, but below the minimum of 5 calls
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        call(false);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(transitions).singleElement().satisfies(t -> {
            assertThat(t.from()).isEqualTo(CircuitState.CLOSED);
            assertThat(t.to()).isEqualTo(CircuitState.OPEN);
            assertThat(t.reason()).contains("100%");
        });
    }

    @Test
    void opensExactlyAtTheFailureRateThreshold() {
        for (int i = 0; i < 6; i++) {
            call(true);
        }
        for (int i = 0; i < 4; i++) {
            call(false);          // 4/10 = 40%: below 50%
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        call(false);              // window slides: oldest success drops out -> 5/10 = 50%
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void oldFailuresSlideOutOfTheWindow() {
        call(false);
        call(false);
        for (int i = 0; i < 10; i++) {
            call(true);           // the two failures are pushed out of the 10-call window
        }
        assertThat(breaker.snapshot().failedCalls()).isZero();
        assertThat(breaker.snapshot().bufferedCalls()).isEqualTo(10);
    }

    @Test
    void openCircuitRejectsCallsUntilTheOpenDurationElapses() {
        openIt();
        assertThat(breaker.tryAcquirePermission()).isFalse();
        clock.advance(Duration.ofMillis(9_999));
        assertThat(breaker.tryAcquirePermission()).isFalse();
        assertThat(breaker.snapshot().remainingOpen()).isEqualTo(Duration.ofMillis(1));

        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.tryAcquirePermission()).isTrue();          // first trial call
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void halfOpenClosesAfterAllTrialCallsSucceed() {
        openIt();
        clock.advance(Duration.ofSeconds(10));
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isFalse();         // only 2 trial calls at a time
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.snapshot().bufferedCalls()).isZero();     // fresh window after recovery
        assertThat(transitions).extracting(CircuitBreaker.Transition::to)
                .containsExactly(CircuitState.OPEN, CircuitState.HALF_OPEN, CircuitState.CLOSED);
    }

    @Test
    void halfOpenReopensOnAnyTrialFailure() {
        openIt();
        clock.advance(Duration.ofSeconds(10));
        call(true);
        call(false);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();         // a full new open period
        assertThat(breaker.snapshot().timesOpened()).isEqualTo(2);
        assertThat(transitions).extracting(CircuitBreaker.Transition::to)
                .containsExactly(CircuitState.OPEN, CircuitState.HALF_OPEN, CircuitState.OPEN);
    }

    @Test
    void peekingDoesNotConsumeTrialSlots() {
        openIt();
        clock.advance(Duration.ofSeconds(10));
        for (int i = 0; i < 5; i++) {
            assertThat(breaker.isCallPermitted()).isTrue();
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);     // peek changed nothing
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.isCallPermitted()).isFalse();
    }

    @Test
    void lateResultsWhileOpenAreIgnored() {
        openIt();
        breaker.onSuccess();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.snapshot().timesOpened()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new CircuitBreakerConfig(5, 6, 50, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig(5, 5, 0, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
