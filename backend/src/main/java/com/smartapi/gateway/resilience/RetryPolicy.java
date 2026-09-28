package com.smartapi.gateway.resilience;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * How many attempts, and how long to wait between them.
 *
 * <p>Exponential backoff: the n-th retry waits {@code initialBackoff x multiplier^(n-1)}, capped at
 * {@code maxBackoff}: 100 ms, 200 ms, 400 ms, ... Waiting longer each time gives a struggling service
 * room to recover instead of hammering it at a fixed rate.
 *
 * <p>Jitter: the wait is multiplied by a random factor in {@code [1 - jitter, 1 + jitter]}. Without it,
 * every client that failed at the same moment retries at the same moment (synchronised "waves").
 * With {@code jitter = 0.2} the 200 ms wait becomes anywhere in 160-240 ms.
 *
 * @param maxAttempts total attempts including the first (3 = 1 try + 2 retries)
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff,
                          double jitter) {

    public RetryPolicy {
        if (maxAttempts < 1 || multiplier < 1 || jitter < 0 || jitter >= 1
                || initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("invalid retry policy");
        }
    }

    /**
     * @param retryNumber 1 for the first retry, 2 for the second, ...
     * @param random      uniform in [0, 1); injected so tests are deterministic
     */
    public Duration backoff(int retryNumber, DoubleSupplier random) {
        double exponential = initialBackoff.toMillis() * Math.pow(multiplier, retryNumber - 1);
        double capped = Math.min(exponential, maxBackoff.toMillis());
        double factor = 1 - jitter + 2 * jitter * random.getAsDouble();
        return Duration.ofMillis(Math.round(capped * factor));
    }
}
