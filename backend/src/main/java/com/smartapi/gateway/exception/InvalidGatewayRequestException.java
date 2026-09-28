package com.smartapi.gateway.exception;

import org.springframework.http.HttpStatus;

/** 400: the gateway URL itself is malformed (no service segment, path traversal, bad characters). */
public class InvalidGatewayRequestException extends GatewayException {

    public InvalidGatewayRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "Invalid gateway request", detail);
    }
}
