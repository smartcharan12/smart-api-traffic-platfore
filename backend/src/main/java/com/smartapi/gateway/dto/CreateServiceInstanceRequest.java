package com.smartapi.gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/service-instances}.
 *
 * @param host   hostname or IPv4 address (no scheme, no path), e.g. {@code localhost} or {@code service-a}
 * @param weight optional relative share of traffic for weighted round robin; defaults to 1
 */
public record CreateServiceInstanceRequest(
        @NotNull
        @Positive
        Long serviceId,

        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$",
                message = "must be a hostname or IPv4 address without scheme or path")
        String host,

        @NotNull
        @Min(1)
        @Max(65535)
        Integer port,

        @Min(1)
        @Max(100)
        Integer weight
) {
}
