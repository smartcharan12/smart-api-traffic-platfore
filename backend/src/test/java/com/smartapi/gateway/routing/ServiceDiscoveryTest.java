package com.smartapi.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import com.smartapi.gateway.ratelimit.RateLimitAlgorithm;
import com.smartapi.gateway.ratelimit.RateLimitDimension;
import com.smartapi.gateway.service.RegistryChangedEvent;
import com.smartapi.gateway.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceDiscoveryTest {

    private final RegistryLookup lookup = mock(RegistryLookup.class);
    private final MutableClock clock = MutableClock.atNoon();
    private final DiscoveredService orders =
            new DiscoveredService(1, "orders-service", "/orders", LoadBalancingStrategyType.ROUND_ROBIN, List.of());

    private ServiceDiscovery discoveryWithTtl(Duration ttl) {
        return new ServiceDiscovery(lookup, clock, new GatewayProperties(
                new GatewayProperties.Proxy(Duration.ofSeconds(1), Duration.ofSeconds(5), 3),
                new GatewayProperties.RateLimit(false, RateLimitAlgorithm.TOKEN_BUCKET, 100, Duration.ofMinutes(1),
                        List.of(RateLimitDimension.IP), false),
                new GatewayProperties.Discovery(ttl)));
    }

    @Test
    void cachesLookupsUntilTheTtlExpires() {
        when(lookup.load("/orders")).thenReturn(Optional.of(orders));
        ServiceDiscovery discovery = discoveryWithTtl(Duration.ofSeconds(5));

        for (int i = 0; i < 100; i++) {
            assertThat(discovery.find("/orders")).contains(orders);
        }
        verify(lookup, times(1)).load("/orders");       // 100 requests, 1 database lookup

        clock.advance(Duration.ofSeconds(5));
        discovery.find("/orders");
        verify(lookup, times(2)).load("/orders");       // expired: reloaded once
    }

    @Test
    void cachesUnknownRoutesToo() {
        when(lookup.load("/nope")).thenReturn(Optional.empty());
        ServiceDiscovery discovery = discoveryWithTtl(Duration.ofSeconds(5));

        for (int i = 0; i < 10; i++) {
            assertThat(discovery.find("/nope")).isEmpty();
        }
        verify(lookup, times(1)).load("/nope");
    }

    @Test
    void registryChangeClearsTheCacheImmediately() {
        when(lookup.load("/orders")).thenReturn(Optional.of(orders));
        ServiceDiscovery discovery = discoveryWithTtl(Duration.ofMinutes(10));
        discovery.find("/orders");

        discovery.onRegistryChanged(new RegistryChangedEvent("instance 3 status: DOWN"));
        discovery.find("/orders");

        verify(lookup, times(2)).load("/orders");
    }

    @Test
    void resultLoadedWhileAnInvalidationHappensIsNotServedAfterwards() {
        DiscoveredService stale = orders;
        DiscoveredService fresh = new DiscoveredService(1, "orders-service", "/orders",
                LoadBalancingStrategyType.WEIGHTED_ROUND_ROBIN, List.of());
        ServiceDiscovery discovery = discoveryWithTtl(Duration.ofMinutes(10));
        // First load: while it is "reading the database", an admin's change commits and invalidates.
        when(lookup.load("/orders"))
                .thenAnswer(invocation -> {
                    discovery.onRegistryChanged(new RegistryChangedEvent("instance 3 status: DOWN"));
                    return Optional.of(stale);
                })
                .thenReturn(Optional.of(fresh));

        assertThat(discovery.find("/orders")).contains(stale); // this request saw the old data (unavoidable)
        assertThat(discovery.find("/orders")).contains(fresh); // but it was not cached for later requests
    }

    @Test
    void zeroTtlDisablesCaching() {
        when(lookup.load("/orders")).thenReturn(Optional.of(orders));
        ServiceDiscovery discovery = discoveryWithTtl(Duration.ZERO);
        discovery.find("/orders");
        discovery.find("/orders");
        verify(lookup, times(2)).load("/orders");
    }
}
