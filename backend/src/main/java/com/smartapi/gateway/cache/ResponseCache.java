package com.smartapi.gateway.cache;

import com.smartapi.gateway.routing.GatewayResponse;
import java.time.Duration;
import java.util.Optional;

/** Shared store for cacheable backend responses. */
public interface ResponseCache {

    Optional<CachedResponse> get(String key);

    void put(String key, GatewayResponse response, Duration ttl);

    /** A stored response plus when it was stored (for the HTTP {@code Age} header). */
    record CachedResponse(GatewayResponse response, long storedAtEpochMillis) {
    }
}
