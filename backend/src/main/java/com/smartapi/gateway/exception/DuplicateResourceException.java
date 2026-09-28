package com.smartapi.gateway.exception;

/** Creating the resource would violate a uniqueness rule. Mapped to 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
