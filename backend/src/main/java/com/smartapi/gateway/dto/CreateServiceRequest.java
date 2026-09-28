package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/services}. Validated before the controller method runs; a violation
 * produces a 400 response with per-field errors (see GlobalExceptionHandler).
 *
 * @param loadBalancingStrategy optional; defaults to ROUND_ROBIN
 */
public record CreateServiceRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$",
                message = "must be lowercase letters, digits and hyphens, e.g. orders-service")
        String name,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^/[a-z0-9][a-z0-9-]*$",
                message = "must be a single path segment starting with '/', e.g. /orders")
        String basePath,

        @Size(max = 500)
        String description,

        LoadBalancingStrategyType loadBalancingStrategy
) {
}
