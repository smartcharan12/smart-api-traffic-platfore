package com.smartapi.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitKeyResolverTest {

    private static RateLimitKeyResolver resolverFor(RateLimitDimension... dimensions) {
        return new RateLimitKeyResolver(new GatewayProperties(
                new GatewayProperties.Proxy(Duration.ofSeconds(1), Duration.ofSeconds(5), 3),
                new GatewayProperties.RateLimit(true, RateLimitAlgorithm.TOKEN_BUCKET, 100, Duration.ofMinutes(1),
                        List.of(dimensions), false),
                new GatewayProperties.Discovery(Duration.ofSeconds(5))));
    }

    @Test
    void ipKey() {
        assertThat(resolverFor(RateLimitDimension.IP).resolve("10.0.0.7", "alice", "GET", "/orders"))
                .isEqualTo("ip:10.0.0.7");
    }

    @Test
    void userKeyGivesEachUserTheirOwnBucket() {
        RateLimitKeyResolver resolver = resolverFor(RateLimitDimension.USER);
        assertThat(resolver.resolve("10.0.0.7", "alice", "GET", "/orders"))
                .isNotEqualTo(resolver.resolve("10.0.0.7", "bob", "GET", "/orders"));
    }

    @Test
    void anonymousUsersFallBackToTheirIp() {
        assertThat(resolverFor(RateLimitDimension.USER).resolve("10.0.0.7", null, "GET", "/orders"))
                .isEqualTo("user:anonymous@10.0.0.7");
    }

    @Test
    void endpointKeyIsSharedByAllClientsAndIgnoresIds() {
        RateLimitKeyResolver resolver = resolverFor(RateLimitDimension.ENDPOINT);
        assertThat(resolver.resolve("10.0.0.7", "alice", "GET", "/orders"))
                .isEqualTo(resolver.resolve("10.0.0.8", "bob", "GET", "/orders"))
                .isEqualTo("endpoint:GET /orders");
        assertThat(resolver.resolve("10.0.0.7", "alice", "POST", "/orders")).isEqualTo("endpoint:POST /orders");
    }

    @Test
    void dimensionsCombine() {
        assertThat(resolverFor(RateLimitDimension.USER, RateLimitDimension.ENDPOINT)
                .resolve("10.0.0.7", "alice", "GET", "/orders"))
                .isEqualTo("user:alice|endpoint:GET /orders");
    }
}
