package com.smartapi.gateway.resilience;

import java.time.Duration;

/**
 * @param slidingWindowSize      number of most recent calls the failure rate is computed over
 * @param minimumCalls           no decision until this many calls are recorded (3 failures out of 3 calls
 *                               right after startup should not open the circuit on a single slow blip)
 * @param failureRateThreshold   percentage (1-100) of failed calls in the window that opens the circuit
 * @param openDuration           how long to reject calls before letting trial calls through
 * @param halfOpenPermittedCalls trial calls allowed in HALF_OPEN; all must succeed to close
 */
public record CircuitBreakerConfig(int slidingWindowSize, int minimumCalls, int failureRateThreshold,
                                   Duration openDuration, int halfOpenPermittedCalls) {

    public CircuitBreakerConfig {
        if (slidingWindowSize < 1 || minimumCalls < 1 || minimumCalls > slidingWindowSize) {
            throw new IllegalArgumentException("need 1 <= minimumCalls <= slidingWindowSize");
        }
        if (failureRateThreshold < 1 || failureRateThreshold > 100) {
            throw new IllegalArgumentException("failureRateThreshold must be 1..100");
        }
        if (openDuration.isNegative() || openDuration.isZero() || halfOpenPermittedCalls < 1) {
            throw new IllegalArgumentException("openDuration must be positive and halfOpenPermittedCalls >= 1");
        }
    }
}
