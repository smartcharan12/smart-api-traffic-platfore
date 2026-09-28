package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.InstanceStatus;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PATCH /api/service-instances/{id}/status}, e.g. {@code {"status": "DOWN"}}. */
public record UpdateInstanceStatusRequest(@NotNull InstanceStatus status) {
}
