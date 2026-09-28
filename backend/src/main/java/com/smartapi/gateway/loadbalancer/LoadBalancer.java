package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Dispatches to the strategy configured for each service. Spring injects every
 * {@link LoadBalancingStrategy} bean as a list, and we index them by type. The gateway calls
 * {@code choose(type, ...)} and never names a concrete strategy class (polymorphism).
 */
@Component
public class LoadBalancer {

    private final Map<LoadBalancingStrategyType, LoadBalancingStrategy> strategies =
            new EnumMap<>(LoadBalancingStrategyType.class);

    public LoadBalancer(List<LoadBalancingStrategy> available) {
        for (LoadBalancingStrategy strategy : available) {
            if (strategies.put(strategy.type(), strategy) != null) {
                throw new IllegalStateException("Two load-balancing strategies registered for " + strategy.type());
            }
        }
        // Fail at startup, not on the first request for a service using the missing strategy.
        for (LoadBalancingStrategyType type : LoadBalancingStrategyType.values()) {
            if (!strategies.containsKey(type)) {
                throw new IllegalStateException("No load-balancing strategy implements " + type);
            }
        }
    }

    public BackendInstance choose(LoadBalancingStrategyType type, long serviceId, List<BackendInstance> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No candidates to choose from");
        }
        return strategies.get(type).choose(serviceId, candidates);
    }
}
