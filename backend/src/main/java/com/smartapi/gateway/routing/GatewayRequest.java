package com.smartapi.gateway.routing;

import org.springframework.http.HttpHeaders;

/**
 * The incoming client request, copied out of the servlet API so the pipeline can be tested without a
 * servlet container.
 *
 * @param path  raw (still percent-encoded) request URI, e.g. /gateway/orders/orders/123
 * @param query raw query string without '?', or null
 * @param body  request body, or null/empty
 */
public record GatewayRequest(String method, String path, String query, HttpHeaders headers, byte[] body,
                             String clientIp, String userId) {
}
