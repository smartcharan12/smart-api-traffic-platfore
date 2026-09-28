package com.smartapi.gateway.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base class for every way a gateway request can fail. Each subclass fixes its own HTTP status, so the
 * mapping from failure to status code lives in one obvious place per failure, and
 * GlobalExceptionHandler needs a single handler for all of them.
 */
public abstract class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    protected GatewayException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    protected GatewayException(HttpStatus status, String title, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    /** Extra headers for the error response (e.g. Retry-After). None by default. */
    public HttpHeaders responseHeaders() {
        return new HttpHeaders();
    }
}
