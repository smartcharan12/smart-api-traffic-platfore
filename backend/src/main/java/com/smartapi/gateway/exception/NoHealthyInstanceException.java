package com.smartapi.gateway.exception;

import org.springframework.http.HttpStatus;

/** 503: the service exists but has no instance we are allowed to route to. */
public class NoHealthyInstanceException extends GatewayException {

    public NoHealthyInstanceException(String serviceName, int registeredInstances) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                registeredInstances == 0
                        ? "Service '%s' has no registered instances".formatted(serviceName)
                        : "All %d instances of service '%s' are DOWN".formatted(registeredInstances, serviceName));
    }
}
