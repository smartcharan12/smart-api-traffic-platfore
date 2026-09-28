package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.entity.ServiceInstance;
import java.time.Instant;

public record ServiceInstanceResponse(
        Long id,
        Long serviceId,
        String serviceName,
        String host,
        int port,
        int weight,
        InstanceStatus status,
        Instant lastHeartbeatAt,
        Instant createdAt
) {
    /** Reads {@code instance.getService()}, so the service must already be loaded (JOIN FETCH). */
    public static ServiceInstanceResponse from(ServiceInstance instance) {
        return new ServiceInstanceResponse(
                instance.getId(),
                instance.getService().getId(),
                instance.getService().getName(),
                instance.getHost(),
                instance.getPort(),
                instance.getWeight(),
                instance.getStatus(),
                instance.getLastHeartbeatAt(),
                instance.getCreatedAt());
    }
}
