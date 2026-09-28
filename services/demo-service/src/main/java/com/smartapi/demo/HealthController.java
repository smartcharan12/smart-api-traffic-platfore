package com.smartapi.demo;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness endpoint. The gateway's automatic health checker (Phase 3) will poll this. */
@RestController
public class HealthController {

    private final InstanceInfo instance;

    public HealthController(InstanceInfo instance) {
        this.instance = instance;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", instance.serviceName(), "instance", instance.instanceId());
    }
}
