package com.smartapi.gateway.config;

import com.smartapi.gateway.ratelimit.RateLimitAlgorithm;
import com.smartapi.gateway.ratelimit.RateLimitDimension;
import com.smartapi.gateway.ratelimit.RateLimitStore;
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
                                @Valid @DefaultValue Discovery discovery,
                                @Valid @DefaultValue CircuitBreaker circuitBreaker,
                                @Valid @DefaultValue Retry retry,
                                @Valid @DefaultValue Cache cache,
                                @Valid @DefaultValue HealthCheck healthCheck,
                                @Valid @DefaultValue RequestLog requestLog) {

    /**
     * Persisted request logs (asynchronous, batched).
     *
     * @param queueCapacity entries waiting to be written; when full, new entries are dropped (and counted)
     * @param retention     rows older than this are deleted hourly
     */
    public record RequestLog(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10000") @Min(1) int queueCapacity,
            @DefaultValue("500") @Min(1) int batchSize,
            @DefaultValue("7d") Duration retention) {
    }

    /**
     * Active health checks: GET {service.healthCheckPath} on every instance, every {@code interval}.
     *
     * @param degradedLatency     a successful probe slower than this marks the instance DEGRADED
     * @param unhealthyThreshold  consecutive failed probes before DOWN
     * @param healthyThreshold    consecutive successful probes before a DOWN instance is HEALTHY again
     * @param threads             probes run in parallel on this many threads
     */
    public record HealthCheck(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration interval,
            @DefaultValue("2s") Duration initialDelay,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("500ms") Duration degradedLatency,
            @DefaultValue("3") @Min(1) int unhealthyThreshold,
            @DefaultValue("2") @Min(1) int healthyThreshold,
            @DefaultValue("8") @Min(1) @Max(64) int threads) {
    }

    /**
     * Shared response cache (Redis) for GET responses the backend marks cacheable via Cache-Control.
     *
     * @param maxTtl       upper bound on the backend's max-age
     * @param maxBodyBytes larger responses are not cached
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration maxTtl,
            @DefaultValue("262144") @Min(1) int maxBodyBytes) {
    }

    /**
     * Per-backend circuit breaker. {@code failureStatuses} are backend responses that count as failures
     * (the backend answered, but is unhealthy); connection errors, timeouts and 502s always count.
     */
    public record CircuitBreaker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") @Min(1) int slidingWindowSize,
            @DefaultValue("5") @Min(1) int minimumCalls,
            @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,
            @DefaultValue("10s") Duration openDuration,
            @DefaultValue("2") @Min(1) int halfOpenPermittedCalls,
            @DefaultValue("500,502,503,504") List<Integer> failureStatuses) {
    }

    /**
     * @param maxAttempts total attempts per client request, including the first (1 disables retries)
     * @param budgetRatio retries allowed per client request on average (0.2 = at most ~20% extra load)
     */
    public record Retry(
            @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
            @DefaultValue("100ms") Duration initialBackoff,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("1s") Duration maxBackoff,
            @DefaultValue("0.2") double jitter,
            @DefaultValue("0.2") double budgetRatio,
            @DefaultValue("20") @Min(1) int budgetMaxTokens) {
    }

    /** @param cacheTtl how long a route lookup is reused before re-reading the registry; 0 disables caching */
    public record Discovery(@DefaultValue("5s") Duration cacheTtl) {
    }

    /**
     * @param connectTimeout  max time to open a TCP connection to an instance
     * @param responseTimeout max time to wait for the instance's response
     */
    public record Proxy(
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration responseTimeout) {
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
            @DefaultValue("false") boolean trustForwardedFor,
            @DefaultValue("LOCAL") RateLimitStore store) {
    }
}
