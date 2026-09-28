package com.smartapi.gateway.exception;

/**
 * The request is well-formed but semantically invalid, e.g. it references a service id that
 * does not exist. Mapped to 400 (not 404: the URL itself was found, the body is wrong).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
