package com.smartapi.gateway.routing;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import java.util.List;

/** A service and all its registered instances, as found in the registry (unfiltered). */
public record DiscoveredService(long id, String name, String basePath, LoadBalancingStrategyType strategy,
                                List<BackendInstance> instances) {
}
