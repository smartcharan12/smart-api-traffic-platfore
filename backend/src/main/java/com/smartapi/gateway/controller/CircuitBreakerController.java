package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.CircuitBreakersResponse;
import com.smartapi.gateway.resilience.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of this gateway's circuit breakers (state is per gateway process). */
@RestController
public class CircuitBreakerController {

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerController(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/circuit-breakers")
    public CircuitBreakersResponse list() {
        return new CircuitBreakersResponse(registry.isEnabled(),
                registry.snapshots().stream().map(CircuitBreakersResponse.Breaker::from).toList(),
                registry.recentTransitions().stream().map(CircuitBreakersResponse.Transition::from).toList());
    }
}
