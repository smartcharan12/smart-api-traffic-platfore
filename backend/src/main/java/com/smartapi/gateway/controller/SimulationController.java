package com.smartapi.gateway.controller;

import com.smartapi.gateway.simulation.SimulationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST is ADMIN-only via SecurityConfig (every non-GET /api/** requires ADMIN). */
@RestController
public class SimulationController {

    public record SimulationRequest(@NotNull Long instanceId, @NotNull SimulationService.Scenario scenario) {
    }

    private final SimulationService simulations;

    public SimulationController(SimulationService simulations) {
        this.simulations = simulations;
    }

    @GetMapping("/api/simulations")
    public Map<String, Object> info() {
        return Map.of("enabled", simulations.isEnabled(), "scenarios", SimulationService.Scenario.values());
    }

    @PostMapping("/api/simulations")
    public SimulationService.Result apply(@Valid @RequestBody SimulationRequest request) {
        return simulations.apply(request.instanceId(), request.scenario());
    }
}
