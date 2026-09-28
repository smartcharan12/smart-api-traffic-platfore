package com.smartapi.demo;

/**
 * Every response says which instance handled it, so load balancing is visible from the outside.
 *
 * @param requestId the X-Request-ID the gateway sent, echoed back to prove it was propagated
 */
public record DemoResponse(String service, String instance, String message, String requestId, Object data) {
}
