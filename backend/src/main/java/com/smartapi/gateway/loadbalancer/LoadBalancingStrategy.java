package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.util.List;

/**
 * Strategy pattern: every algorithm has the same shape (pick one instance from a list), so the
 * gateway can call {@code choose} without knowing which algorithm runs. Adding a new algorithm means
 * adding a class, not editing the gateway (open/closed principle).
 */
public interface LoadBalancingStrategy {

    /** Which {@code services.load_balancing_strategy} value this implementation handles. */
    LoadBalancingStrategyType type();

    /**
     * @param serviceId  lets stateful strategies keep separate state per service
     * @param candidates routable instances, non-empty, in a stable order (by id)
     */
    BackendInstance choose(long serviceId, List<BackendInstance> candidates);
}
