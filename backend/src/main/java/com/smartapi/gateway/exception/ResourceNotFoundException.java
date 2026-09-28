package com.smartapi.gateway.exception;

/** The resource addressed by the URL does not exist. Mapped to 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s with id %s not found".formatted(resource, id));
    }
}
