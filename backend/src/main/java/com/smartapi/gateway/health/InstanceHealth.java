package com.smartapi.gateway.health;

import com.smartapi.gateway.entity.InstanceStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * Live health record of one instance, updated after every probe. Mutable, guarded by {@code this}:
 * written by the health-check thread, read by API requests.
 */
public final class InstanceHealth {

    /** Immutable copy for APIs. */
    public record Snapshot(long instanceId, String serviceName, String address, InstanceStatus status,
                           Long lastLatencyMs, int consecutiveFailures, long totalChecks, long successfulChecks,
                           Instant lastCheckAt, Instant lastSuccessAt, Instant upSince, String lastError) {

        public Double availabilityPercent() {
            return totalChecks == 0 ? null : Math.round(1000.0 * successfulChecks / totalChecks) / 10.0;
        }

        public Long uptimeSeconds(Instant now) {
            return upSince == null ? null : Duration.between(upSince, now).toSeconds();
        }
    }

    private final long instanceId;
    private final String serviceName;
    private final String address;
    private InstanceStatus status;
    private Long lastLatencyMs;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private int consecutiveSlow;
    private long totalChecks;
    private long successfulChecks;
    private Instant lastCheckAt;
    private Instant lastSuccessAt;
    private Instant upSince;
    private String lastError;

    public InstanceHealth(long instanceId, String serviceName, String address, InstanceStatus initial) {
        this.instanceId = instanceId;
        this.serviceName = serviceName;
        this.address = address;
        this.status = initial;
    }

    /** Applies one probe result and returns the (possibly unchanged) status. */
    public synchronized InstanceStatus record(boolean success, long latencyMs, String error, Instant now,
                                              HealthEvaluator.Thresholds thresholds) {
        totalChecks++;
        lastCheckAt = now;
        lastLatencyMs = latencyMs;
        if (success) {
            successfulChecks++;
            consecutiveSuccesses++;
            consecutiveFailures = 0;
            lastSuccessAt = now;
            lastError = null;
            consecutiveSlow = latencyMs > thresholds.degradedLatencyMs() ? consecutiveSlow + 1 : 0;
        } else {
            consecutiveFailures++;
            consecutiveSuccesses = 0;
            consecutiveSlow = 0;
            lastError = error;
        }
        InstanceStatus next = HealthEvaluator.next(status, success, latencyMs, consecutiveSuccesses,
                consecutiveFailures, consecutiveSlow, thresholds);
        if (next == InstanceStatus.DOWN) {
            upSince = null;
        } else if (upSince == null) {
            upSince = now;   // came up (from DOWN or first successful check)
        }
        status = next;
        return next;
    }

    /** When someone changes the status by hand (PATCH), start from it. */
    public synchronized void overrideStatus(InstanceStatus status) {
        this.status = status;
    }

    public synchronized InstanceStatus status() {
        return status;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(instanceId, serviceName, address, status, lastLatencyMs, consecutiveFailures,
                totalChecks, successfulChecks, lastCheckAt, lastSuccessAt, upSince, lastError);
    }
}
