package com.smartapi.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 503: instances are registered as routable, but every one we tried refused the connection (the
 * process is down or unreachable). The request never reached a backend, so it is safe to retry later.
 */
public class UpstreamUnavailableException extends GatewayException {

    public UpstreamUnavailableException(String serviceName, int attempts) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "Could not connect to any instance of '%s' (%d attempted)".formatted(serviceName, attempts));
    }
}
