package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.HealthResponse;
import com.smartapi.gateway.service.SystemHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard-facing health endpoint. Returns 503 when a required dependency (the database) is down so that load balancers
 * and scripts can rely on the status code alone. (Spring Actuator's /actuator/health also exists
 * and is what Docker/Kubernetes probes will use.)
 */
@RestController
public class HealthController {

    private final SystemHealthService healthService;

    public HealthController(SystemHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse health = healthService.check();
        // DEGRADED (Redis down, local fallbacks in use) still serves traffic, so it is 200.
        HttpStatus status = "DOWN".equals(health.status()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(health);
    }
}
