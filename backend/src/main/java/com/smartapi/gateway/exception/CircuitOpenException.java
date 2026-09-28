package com.smartapi.gateway.exception;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 503: every routable instance of the service has an OPEN circuit. The gateway fails fast instead of
 * sending traffic to instances that are known to be failing (and instead of making the client wait
 * for timeouts). Retry-After says when the first circuit will allow a trial call.
 */
public class CircuitOpenException extends GatewayException {

    private final Duration retryAfter;

    public CircuitOpenException(String serviceName, Duration retryAfter) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "Circuit breaker open for all instances of '%s'".formatted(serviceName));
        this.retryAfter = retryAfter;
    }

    @Override
    public HttpHeaders responseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
        return headers;
    }
}
