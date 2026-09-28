package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.resilience.CircuitState;
import java.time.Instant;

/**
 * One row of {@code GET /api/health/instances}: everything the gateway knows about an instance.
 * Probe statistics are null on a gateway that is not the health-check leader (see HealthCheckService).
 */
public record InstanceHealthView(
        long instanceId,
        String serviceName,
        String address,
        int weight,
        InstanceStatus status,
        Instant lastHeartbeatAt,
        Long latencyMs,
        Double availabilityPercent,
        Long uptimeSeconds,
        Integer consecutiveFailures,
        Long totalChecks,
        Instant lastCheckAt,
        String lastError,
        int activeConnections,
        CircuitState circuitState
) {
}
