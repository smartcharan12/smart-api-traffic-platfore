package com.smartapi.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 504: we connected and sent the request, but the instance did not answer in time. The backend may
 * still be processing it, so this is NOT automatically retried on another instance (a retried POST
 * could charge a payment twice). Phase 3's retry engine will handle idempotent retries.
 */
public class UpstreamTimeoutException extends GatewayException {

    public UpstreamTimeoutException(String instance, Throwable cause) {
        super(HttpStatus.GATEWAY_TIMEOUT, "Gateway timeout",
                "Instance %s did not respond in time".formatted(instance), cause);
    }
}
