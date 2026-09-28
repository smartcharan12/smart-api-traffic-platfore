package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.entity.ServiceInstance;

/**
 * Immutable snapshot of a service instance, used on the request path. The gateway works with this
 * record rather than the JPA entity so that routing code never triggers lazy loading or holds a
 * database session, and so load-balancer tests can create instances with a plain constructor.
 */
public record BackendInstance(long id, long serviceId, String host, int port, int weight, InstanceStatus status) {

    public static BackendInstance from(ServiceInstance instance, long serviceId) {
        return new BackendInstance(instance.getId(), serviceId, instance.getHost(), instance.getPort(),
                instance.getWeight(), instance.getStatus());
    }

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    public String address() {
        return host + ":" + port;
    }
}
