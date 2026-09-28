package com.smartapi.gateway.health;

import com.smartapi.gateway.entity.InstanceStatus;

/**
 * Pure status rules (no I/O), so every transition is unit-testable.
 * <pre>
 *   probe OK and fast   -> HEALTHY
 *   probe OK but slow   -> DEGRADED            (latency above degradedLatency on 2 consecutive probes)
 *   probe failed        -> DEGRADED, and DOWN after unhealthyThreshold consecutive failures
 *   while DOWN          -> stays DOWN until healthyThreshold consecutive successes
 * </pre>
 * The thresholds give hysteresis: one lost probe doesn't take an instance out of rotation, one lucky
 * probe doesn't put a crashing instance back in, and one slow probe (a GC pause, a busy host) doesn't
 * flip HEALTHY to DEGRADED. Every status change costs a database write, a routing-cache invalidation
 * and a broadcast to all gateways, so flapping on noise is not free.
 */
public final class HealthEvaluator {

    /** Consecutive slow (but successful) probes before a HEALTHY instance is marked DEGRADED. */
    public static final int SLOW_PROBES_BEFORE_DEGRADED = 2;

    public record Thresholds(long degradedLatencyMs, int unhealthyThreshold, int healthyThreshold) {
    }

    private HealthEvaluator() {
    }

    public static InstanceStatus next(InstanceStatus current, boolean success, long latencyMs,
                                      int consecutiveSuccesses, int consecutiveFailures, int consecutiveSlow,
                                      Thresholds t) {
        if (!success) {
            if (consecutiveFailures >= t.unhealthyThreshold() || current == InstanceStatus.DOWN) {
                return InstanceStatus.DOWN;
            }
            return InstanceStatus.DEGRADED;
        }
        if (current == InstanceStatus.DOWN && consecutiveSuccesses < t.healthyThreshold()) {
            return InstanceStatus.DOWN;
        }
        if (latencyMs <= t.degradedLatencyMs()) {
            return InstanceStatus.HEALTHY;
        }
        // Slow: stay where we are until slowness persists (a DEGRADED instance stays DEGRADED).
        if (current == InstanceStatus.DEGRADED || consecutiveSlow >= SLOW_PROBES_BEFORE_DEGRADED) {
            return InstanceStatus.DEGRADED;
        }
        return current == InstanceStatus.UNKNOWN || current == InstanceStatus.DOWN ? InstanceStatus.HEALTHY : current;
    }
}
