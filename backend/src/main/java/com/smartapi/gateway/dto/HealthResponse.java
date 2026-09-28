package com.smartapi.gateway.dto;

import java.time.Instant;
import java.util.Map;

/**
 * @param status     UP only if every component is UP
 * @param components per-dependency status, e.g. {@code {"database": "UP"}}
 */
public record HealthResponse(String status, Map<String, String> components, Instant timestamp) {
}
