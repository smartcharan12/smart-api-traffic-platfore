package com.smartapi.gateway.ratelimit;

/**
 * What a rate-limit bucket belongs to. Dimensions can be combined, e.g. [USER, ENDPOINT] gives each
 * user a separate budget per endpoint.
 */
public enum RateLimitDimension {
    /** Client IP address: limits anonymous clients, but users behind one NAT share a budget. */
    IP,
    /** Authenticated user (X-User-Id until JWT arrives in Phase 4); falls back to IP when absent. */
    USER,
    /** HTTP method + service route, e.g. "GET /orders": one budget for everyone, protects the backend. */
    ENDPOINT
}
