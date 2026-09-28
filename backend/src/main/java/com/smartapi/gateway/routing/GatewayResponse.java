package com.smartapi.gateway.routing;

import org.springframework.http.HttpHeaders;

/** What the backend instance answered: returned to the client as-is (after header filtering). */
public record GatewayResponse(int status, HttpHeaders headers, byte[] body) {
}
