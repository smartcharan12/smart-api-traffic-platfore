package com.smartapi.gateway.support;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.ratelimit.RateLimitAlgorithm;
import com.smartapi.gateway.ratelimit.RateLimitDimension;
import java.time.Duration;
import java.util.List;

/** GatewayProperties with the same defaults as application.yml, for tests that build beans by hand. */
public final class TestProperties {

    private TestProperties() {
    }

    public static GatewayProperties.Proxy proxy() {
        return new GatewayProperties.Proxy(Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    public static GatewayProperties.RateLimit rateLimit(List<RateLimitDimension> keyBy) {
        return new GatewayProperties.RateLimit(true, RateLimitAlgorithm.TOKEN_BUCKET, 100, Duration.ofMinutes(1),
                keyBy, false);
    }

    public static GatewayProperties.CircuitBreaker circuitBreaker() {
        return new GatewayProperties.CircuitBreaker(true, 10, 5, 50, Duration.ofSeconds(10), 2,
                List.of(500, 502, 503, 504));
    }

    public static GatewayProperties.Retry retry() {
        return new GatewayProperties.Retry(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1), 0.2, 0.2, 20);
    }

    public static GatewayProperties withKeyBy(RateLimitDimension... dimensions) {
        return new GatewayProperties(proxy(), rateLimit(List.of(dimensions)),
                new GatewayProperties.Discovery(Duration.ofSeconds(5)), circuitBreaker(), retry());
    }

    public static GatewayProperties withDiscoveryTtl(Duration ttl) {
        return new GatewayProperties(proxy(), rateLimit(List.of(RateLimitDimension.IP)),
                new GatewayProperties.Discovery(ttl), circuitBreaker(), retry());
    }
}
