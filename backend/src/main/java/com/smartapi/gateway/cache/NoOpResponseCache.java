package com.smartapi.gateway.cache;

import com.smartapi.gateway.routing.GatewayResponse;
import java.time.Duration;
import java.util.Optional;

/** Used when {@code gateway.cache.enabled=false}. */
public class NoOpResponseCache implements ResponseCache {

    @Override
    public Optional<CachedResponse> get(String key) {
        return Optional.empty();
    }

    @Override
    public void put(String key, GatewayResponse response, Duration ttl) {
    }
}
