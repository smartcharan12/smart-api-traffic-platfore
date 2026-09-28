package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.time.Instant;

/** What the API returns for a service. Decoupled from the entity so schema changes don't leak to clients. */
public record ServiceResponse(
        Long id,
        String name,
        String basePath,
        String description,
        LoadBalancingStrategyType loadBalancingStrategy,
        String healthCheckPath,
        Instant createdAt,
        Instant updatedAt
) {
    public static ServiceResponse from(ApiService service) {
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getBasePath(),
                service.getDescription(),
                service.getLoadBalancingStrategy(),
                service.getHealthCheckPath(),
                service.getCreatedAt(),
                service.getUpdatedAt());
    }
}
