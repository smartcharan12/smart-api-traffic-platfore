package com.smartapi.gateway.exception;

import org.springframework.http.HttpStatus;

/** 502: the instance was reached but the exchange broke (connection reset, invalid response). */
public class BadGatewayException extends GatewayException {

    public BadGatewayException(String instance, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "Bad gateway",
                "Invalid or interrupted response from instance %s".formatted(instance), cause);
    }
}
