package com.smartapi.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Identity of this process, e.g. service "orders-service", instance "orders-9001". */
@Component
public class InstanceInfo {

    private final String serviceName;
    private final String instanceId;

    public InstanceInfo(@Value("${demo.service-name}") String serviceName, @Value("${server.port}") int port) {
        this.serviceName = serviceName;
        this.instanceId = serviceName.replace("-service", "") + "-" + port;
    }

    public String serviceName() {
        return serviceName;
    }

    public String instanceId() {
        return instanceId;
    }

    public DemoResponse respond(String message, Object data, String requestId) {
        return new DemoResponse(serviceName, instanceId, message, requestId, data);
    }
}
