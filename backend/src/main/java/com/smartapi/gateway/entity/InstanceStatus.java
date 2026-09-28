package com.smartapi.gateway.entity;

/**
 * Health of a single service instance. New instances start as {@link #UNKNOWN} until the
 * health checker (Phase 3) has probed them.
 */
public enum InstanceStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    DOWN
}
