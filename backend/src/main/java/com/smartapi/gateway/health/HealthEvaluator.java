package com.smartapi.gateway.health;

import com.smartapi.gateway.entity.InstanceStatus;

/**
 * Pure status rules (no I/O), so every transition is unit-testable.
 * <pre>
 *   probe OK and fast   -> HEALTHY
 *   probe OK but slow   -> DEGRADED            (latency above degradedLatency)
 *   probe failed        -> DEGRADED, and DOWN after unhealthyThreshold consecutive failures
 *   while DOWN          -> stays DOWN until healthyThreshold consecutive successes
 * </pre>
 * The two thresholds give hysteresis: one lost probe doesn't take an instance out of rotation, and one
 * lucky probe doesn't put a crashing instance back in.
 */
public final class HealthEvaluator {

    public record Thresholds(long degradedLatencyMs, int unhealthyThreshold, int healthyThreshold) {
    }

    private HealthEvaluator() {
    }

    public static InstanceStatus next(InstanceStatus current, boolean success, long latencyMs,
                                      int consecutiveSuccesses, int consecutiveFailures, Thresholds t) {
        if (!success) {
            if (consecutiveFailures >= t.unhealthyThreshold() || current == InstanceStatus.DOWN) {
                return InstanceStatus.DOWN;
            }
            return InstanceStatus.DEGRADED;
        }
        if (current == InstanceStatus.DOWN && consecutiveSuccesses < t.healthyThreshold()) {
            return InstanceStatus.DOWN;
        }
        return latencyMs > t.degradedLatencyMs() ? InstanceStatus.DEGRADED : InstanceStatus.HEALTHY;
    }
}
