package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.InstanceHealthView;
import com.smartapi.gateway.health.HealthCheckService;
import com.smartapi.gateway.health.InstanceHealthReporter;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InstanceHealthController {

    private final InstanceHealthReporter reporter;
    private final HealthCheckService healthCheckService;

    public InstanceHealthController(InstanceHealthReporter reporter, HealthCheckService healthCheckService) {
        this.reporter = reporter;
        this.healthCheckService = healthCheckService;
    }

    @GetMapping("/api/health/instances")
    public Map<String, Object> instances() {
        List<InstanceHealthView> instances = reporter.report();
        return Map.of("healthChecksEnabled", healthCheckService.isEnabled(),
                "thisGatewayIsChecker", healthCheckService.isLeader(),
                "instances", instances);
    }

    /** Runs one health-check cycle now (demos, and tests without waiting for the schedule). */
    @PostMapping("/api/health/instances/check")
    public List<HealthCheckService.StatusChange> checkNow() {
        return healthCheckService.runChecks();
    }
}
