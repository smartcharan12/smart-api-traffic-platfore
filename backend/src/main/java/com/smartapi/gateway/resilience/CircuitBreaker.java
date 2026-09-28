package com.smartapi.gateway.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Circuit breaker for one backend instance, implemented as a small state machine.
 *
 * <p>CLOSED: calls flow; each outcome goes into a ring buffer of the last {@code slidingWindowSize}
 * calls. Once at least {@code minimumCalls} are recorded and the failure rate reaches the threshold,
 * the circuit OPENS.
 * <br>OPEN: calls are rejected immediately (the gateway doesn't even try the instance) until
 * {@code openDuration} has passed.
 * <br>HALF_OPEN: up to {@code halfOpenPermittedCalls} trial calls are let through. If they all succeed
 * the circuit CLOSES (with a fresh window); if any fails it OPENS again for another full duration.
 *
 * <p>Protocol for callers: {@link #tryAcquirePermission()} before the call; if it returned true, exactly
 * one of {@link #onSuccess()} / {@link #onFailure()} after it.
 *
 * <p>Thread safety: all state is guarded by {@code this}. One breaker exists per instance, so the
 * lock only serialises requests to the same instance, and the critical sections are a few field updates.
 */
public final class CircuitBreaker {

    /** A state change, e.g. CLOSED -> OPEN, published to the registry for metrics/logs/dashboard. */
    public record Transition(String name, CircuitState from, CircuitState to, Instant at, String reason) {
    }

    /** Point-in-time view for APIs and metrics. */
    public record Snapshot(String name, CircuitState state, int bufferedCalls, int failedCalls,
                           double failureRatePercent, Instant openedAt, Duration remainingOpen, long timesOpened) {
    }

    private final String name;
    private final CircuitBreakerConfig config;
    private final Clock clock;
    private final Consumer<Transition> listener;

    // --- guarded by this ---
    private CircuitState state = CircuitState.CLOSED;
    private final boolean[] outcomes;     // ring buffer: true = failure
    private int nextSlot;
    private int bufferedCalls;
    private int failedCalls;
    private long openedAtMillis;
    private int halfOpenInFlight;
    private int halfOpenSucceeded;
    private long timesOpened;

    public CircuitBreaker(String name, CircuitBreakerConfig config, Clock clock, Consumer<Transition> listener) {
        this.name = name;
        this.config = config;
        this.clock = clock;
        this.listener = listener;
        this.outcomes = new boolean[config.slidingWindowSize()];
    }

    /** May a call be made now? Side effect: may move OPEN -> HALF_OPEN and reserve a trial slot. */
    public synchronized boolean tryAcquirePermission() {
        if (state == CircuitState.OPEN && openDurationElapsed()) {
            transitionTo(CircuitState.HALF_OPEN, "open duration of " + config.openDuration().toSeconds() + "s elapsed");
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (halfOpenInFlight + halfOpenSucceeded < config.halfOpenPermittedCalls()) {
                    halfOpenInFlight++;
                    yield true;
                }
                yield false;
            }
        };
    }

    /**
     * Same answer as {@link #tryAcquirePermission()} but without any side effect. Used to filter
     * load-balancer candidates before one is chosen, so unchosen instances don't consume trial slots.
     */
    public synchronized boolean isCallPermitted() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> openDurationElapsed();
            case HALF_OPEN -> halfOpenInFlight + halfOpenSucceeded < config.halfOpenPermittedCalls();
        };
    }

    public synchronized void onSuccess() {
        switch (state) {
            case CLOSED -> record(false);
            case HALF_OPEN -> {
                halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
                halfOpenSucceeded++;
                if (halfOpenSucceeded >= config.halfOpenPermittedCalls()) {
                    transitionTo(CircuitState.CLOSED, halfOpenSucceeded + " trial call(s) succeeded");
                }
            }
            case OPEN -> {
                // A call that started before the circuit opened finished late. It doesn't reopen or close anything.
            }
        }
    }

    public synchronized void onFailure() {
        switch (state) {
            case CLOSED -> {
                record(true);
                if (bufferedCalls >= config.minimumCalls() && failureRate() >= config.failureRateThreshold()) {
                    transitionTo(CircuitState.OPEN, "failure rate %.0f%% (%d of last %d calls)"
                            .formatted(failureRate(), failedCalls, bufferedCalls));
                }
            }
            case HALF_OPEN -> transitionTo(CircuitState.OPEN, "trial call failed");
            case OPEN -> {
            }
        }
    }

    public synchronized Snapshot snapshot() {
        Duration remaining = state == CircuitState.OPEN
                ? Duration.ofMillis(Math.max(0, openedAtMillis + config.openDuration().toMillis() - clock.millis()))
                : Duration.ZERO;
        return new Snapshot(name, state, bufferedCalls, failedCalls, failureRate(),
                state == CircuitState.CLOSED ? null : Instant.ofEpochMilli(openedAtMillis), remaining, timesOpened);
    }

    public synchronized CircuitState state() {
        return state;
    }

    public String name() {
        return name;
    }

    /** O(1): overwrite the oldest outcome, keeping the failure count in step. */
    private void record(boolean failure) {
        if (bufferedCalls == outcomes.length) {
            if (outcomes[nextSlot]) {
                failedCalls--;
            }
        } else {
            bufferedCalls++;
        }
        outcomes[nextSlot] = failure;
        if (failure) {
            failedCalls++;
        }
        nextSlot = (nextSlot + 1) % outcomes.length;
    }

    private double failureRate() {
        return bufferedCalls == 0 ? 0 : 100.0 * failedCalls / bufferedCalls;
    }

    private boolean openDurationElapsed() {
        return clock.millis() >= openedAtMillis + config.openDuration().toMillis();
    }

    private void transitionTo(CircuitState target, String reason) {
        CircuitState previous = state;
        state = target;
        switch (target) {
            case OPEN -> {
                openedAtMillis = clock.millis();
                timesOpened++;
            }
            case HALF_OPEN -> {
                halfOpenInFlight = 0;
                halfOpenSucceeded = 0;
            }
            case CLOSED -> {
                // Start clean: failures from before the outage must not count against the recovered instance.
                java.util.Arrays.fill(outcomes, false);
                nextSlot = 0;
                bufferedCalls = 0;
                failedCalls = 0;
            }
        }
        // Called while holding the lock: listeners must be fast and must not call back into this breaker.
        listener.accept(new Transition(name, previous, target, clock.instant(), reason));
    }
}
