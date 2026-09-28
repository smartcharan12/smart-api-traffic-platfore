package com.smartapi.gateway.config;

import com.smartapi.gateway.ratelimit.RateLimitAlgorithm;
import com.smartapi.gateway.ratelimit.RateLimitDimension;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding of the {@code gateway.*} settings in application.yml. Validated at startup: an invalid
 * value (e.g. {@code limit: 0}) stops the application with a clear message instead of misbehaving later.
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(@Valid @DefaultValue Proxy proxy, @Valid @DefaultValue RateLimit rateLimit,
                                @Valid @DefaultValue Discovery discovery) {

    /** @param cacheTtl how long a route lookup is reused before re-reading the registry; 0 disables caching */
    public record Discovery(@DefaultValue("5s") Duration cacheTtl) {
    }

    /**
     * @param connectTimeout     max time to open a TCP connection to an instance
     * @param responseTimeout    max time to wait for the instance's response
     * @param maxConnectAttempts how many different instances to try when connections are refused
     */
    public record Proxy(
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration responseTimeout,
            @DefaultValue("3") @Min(1) @Max(10) int maxConnectAttempts) {
    }

    /**
     * @param limit             requests allowed per {@code window} (token bucket: capacity = limit,
     *                          refill = limit / window)
     * @param keyBy             which dimensions form the bucket key
     * @param trustForwardedFor use X-Forwarded-For for the client IP. Only safe when the gateway sits
     *                          behind a proxy you control that overwrites the header.
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("TOKEN_BUCKET") RateLimitAlgorithm algorithm,
            @DefaultValue("100") @Min(1) int limit,
            @DefaultValue("1m") Duration window,
            @DefaultValue("IP") @NotEmpty List<RateLimitDimension> keyBy,
            @DefaultValue("false") boolean trustForwardedFor) {
    }
}
