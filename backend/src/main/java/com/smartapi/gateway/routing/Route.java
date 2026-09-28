package com.smartapi.gateway.routing;

/**
 * A parsed gateway URL. {@code /gateway/orders/orders/123} becomes basePath {@code /orders} (which
 * service) and downstreamPath {@code /orders/123} (what to request from the instance).
 */
public record Route(String basePath, String downstreamPath) {
}
