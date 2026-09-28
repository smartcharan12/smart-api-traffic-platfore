package com.smartapi.gateway.routing;

/**
 * Internal signal: the TCP connection to an instance could not be opened (refused, unresolvable host,
 * connect timeout). The request never left the gateway, so trying another instance is safe even for a
 * POST. Never reaches the client; GatewayService turns "all attempts failed" into a 503.
 */
public class UpstreamConnectException extends RuntimeException {

    public UpstreamConnectException(String instance, Throwable cause) {
        super("Cannot connect to " + instance + ": " + cause.getMessage(), cause);
    }
}
