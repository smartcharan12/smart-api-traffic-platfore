package com.smartapi.gateway.dto;

import com.smartapi.gateway.resilience.CircuitBreaker;
import com.smartapi.gateway.resilience.CircuitState;
import java.time.Instant;
import java.util.List;

/** Body of {@code GET /api/circuit-breakers}: current state of every breaker plus recent transitions. */
public record CircuitBreakersResponse(boolean enabled, List<Breaker> breakers, List<Transition> recentTransitions) {

    public record Breaker(String address, CircuitState state, int bufferedCalls, int failedCalls,
                          double failureRatePercent, Instant openedAt, long remainingOpenMs, long timesOpened) {

        public static Breaker from(CircuitBreaker.Snapshot s) {
            return new Breaker(s.name(), s.state(), s.bufferedCalls(), s.failedCalls(),
                    Math.round(s.failureRatePercent() * 10) / 10.0, s.openedAt(), s.remainingOpen().toMillis(),
                    s.timesOpened());
        }
    }

    public record Transition(String address, CircuitState from, CircuitState to, Instant at, String reason) {

        public static Transition from(CircuitBreaker.Transition t) {
            return new Transition(t.name(), t.from(), t.to(), t.at(), t.reason());
        }
    }
}
