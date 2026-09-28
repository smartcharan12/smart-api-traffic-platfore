package com.smartapi.gateway.controller;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.ratelimit.FallbackRateLimiter;
import com.smartapi.gateway.ratelimit.RateLimiter;
import com.smartapi.gateway.redis.RedisGuard;
import com.smartapi.gateway.resilience.CircuitState;
import com.smartapi.gateway.resilience.RetryBudget;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the effective traffic-management settings of THIS gateway process. */
@RestController
public class GatewaySettingsController {

    private final GatewayProperties properties;
    private final RateLimiter rateLimiter;
    private final RedisGuard redisGuard;
    private final RetryBudget retryBudget;

    public GatewaySettingsController(GatewayProperties properties, RateLimiter rateLimiter, RedisGuard redisGuard,
                                     RetryBudget retryBudget) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.redisGuard = redisGuard;
        this.retryBudget = retryBudget;
    }

    @GetMapping("/api/gateway/settings")
    public Map<String, Object> settings() {
        GatewayProperties.RateLimit rl = properties.rateLimit();
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("enabled", rl.enabled());
        rateLimit.put("algorithm", rl.algorithm());
        rateLimit.put("limit", rl.limit());
        rateLimit.put("windowSeconds", rl.window().toSeconds());
        rateLimit.put("keyBy", rl.keyBy());
        rateLimit.put("store", rl.store());
        rateLimit.put("localFallbackDecisions",
                rateLimiter instanceof FallbackRateLimiter f ? f.fallbackDecisions() : 0);

        GatewayProperties.CircuitBreaker cb = properties.circuitBreaker();
        GatewayProperties.Retry retry = properties.retry();
        return Map.of(
                "rateLimit", rateLimit,
                "redis", Map.of("available", redisGuard.state() != CircuitState.OPEN,
                        "failures", redisGuard.failures(), "skippedCalls", redisGuard.skipped()),
                "circuitBreaker", Map.of("enabled", cb.enabled(), "slidingWindowSize", cb.slidingWindowSize(),
                        "minimumCalls", cb.minimumCalls(), "failureRateThreshold", cb.failureRateThreshold(),
                        "openDurationSeconds", cb.openDuration().toSeconds(),
                        "halfOpenPermittedCalls", cb.halfOpenPermittedCalls()),
                "retry", Map.of("maxAttempts", retry.maxAttempts(), "initialBackoffMs", retry.initialBackoff().toMillis(),
                        "multiplier", retry.multiplier(), "maxBackoffMs", retry.maxBackoff().toMillis(),
                        "jitter", retry.jitter(), "budgetRatio", retry.budgetRatio(),
                        "budgetTokensAvailable", retryBudget.available()),
                "cache", Map.of("enabled", properties.cache().enabled(),
                        "maxTtlSeconds", properties.cache().maxTtl().toSeconds()));
    }
}
