package com.smartapi.gateway.routing;

import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Removes instances that must not receive traffic. Only DOWN is excluded:
 * <ul>
 *   <li>HEALTHY: obviously routable.</li>
 *   <li>DEGRADED: slow or partially failing, but still serving. Excluding it could overload the rest.</li>
 *   <li>UNKNOWN: not checked yet. We "fail open": a new instance receives traffic immediately.
 *       The alternative (fail closed) would mean nothing routes until the Phase 3 health checker
 *       has probed every instance.</li>
 * </ul>
 */
@Component
public class HealthFilter {

    private static final Set<InstanceStatus> ROUTABLE =
            EnumSet.of(InstanceStatus.HEALTHY, InstanceStatus.DEGRADED, InstanceStatus.UNKNOWN);

    public List<BackendInstance> routable(List<BackendInstance> instances) {
        return instances.stream().filter(instance -> ROUTABLE.contains(instance.status())).toList();
    }
}
