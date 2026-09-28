package com.smartapi.gateway.entity;

/** Which algorithm the gateway uses to pick an instance of a service (implemented in Phase 2). */
public enum LoadBalancingStrategyType {
    ROUND_ROBIN,
    WEIGHTED_ROUND_ROBIN,
    LEAST_CONNECTIONS
}
