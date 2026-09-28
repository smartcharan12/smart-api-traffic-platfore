package com.smartapi.gateway.health;

import com.smartapi.gateway.dto.InstanceHealthView;
import com.smartapi.gateway.dto.ServiceInstanceResponse;
import com.smartapi.gateway.loadbalancer.ActiveConnectionTracker;
import com.smartapi.gateway.resilience.CircuitBreaker;
import com.smartapi.gateway.resilience.CircuitBreakerRegistry;
import com.smartapi.gateway.resilience.CircuitState;
import com.smartapi.gateway.service.ServiceInstanceService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Joins four sources per instance: registry (status, heartbeat), probes, circuit breaker, connections. */
@Component
public class InstanceHealthReporter {

    private final ServiceInstanceService instanceService;
    private final HealthCheckService healthCheckService;
    private final CircuitBreakerRegistry breakers;
    private final ActiveConnectionTracker connections;
    private final Clock clock;

    public InstanceHealthReporter(ServiceInstanceService instanceService, HealthCheckService healthCheckService,
                                  CircuitBreakerRegistry breakers, ActiveConnectionTracker connections, Clock clock) {
        this.instanceService = instanceService;
        this.healthCheckService = healthCheckService;
        this.breakers = breakers;
        this.connections = connections;
        this.clock = clock;
    }

    public List<InstanceHealthView> report() {
        Map<Long, InstanceHealth.Snapshot> probes = healthCheckService.snapshots().stream()
                .collect(Collectors.toMap(InstanceHealth.Snapshot::instanceId, Function.identity()));
        Instant now = clock.instant();
        return instanceService.findAll(null).stream().map(i -> view(i, probes.get(i.id()), now)).toList();
    }

    private InstanceHealthView view(ServiceInstanceResponse i, InstanceHealth.Snapshot p, Instant now) {
        String address = i.host() + ":" + i.port();
        CircuitState circuit = breakers.find(address).map(CircuitBreaker::state).orElse(CircuitState.CLOSED);
        return new InstanceHealthView(i.id(), i.serviceName(), address, i.weight(), i.status(), i.lastHeartbeatAt(),
                p == null ? null : p.lastLatencyMs(),
                p == null ? null : p.availabilityPercent(),
                p == null ? null : p.uptimeSeconds(now),
                p == null ? null : p.consecutiveFailures(),
                p == null ? null : p.totalChecks(),
                p == null ? null : p.lastCheckAt(),
                p == null ? null : p.lastError(),
                connections.activeConnections(i.id()), circuit);
    }
}
