package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.HealthResponse;
import com.smartapi.gateway.service.SystemHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard-facing health endpoint. Returns 503 when a dependency is down so that load balancers
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
        HttpStatus status = "UP".equals(health.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }
}
