package com.smartapi.gateway.exception;

import org.springframework.http.HttpStatus;

/** 404: no registered service owns this base path. */
public class RouteNotFoundException extends GatewayException {

    public RouteNotFoundException(String basePath) {
        super(HttpStatus.NOT_FOUND, "Route not found", "No service is registered for path '%s'".formatted(basePath));
    }
}
