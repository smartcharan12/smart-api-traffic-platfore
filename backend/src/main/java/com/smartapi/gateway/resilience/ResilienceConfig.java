package com.smartapi.gateway.resilience;

import com.smartapi.gateway.config.GatewayProperties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    @Bean
    public RetryPolicy retryPolicy(GatewayProperties properties) {
        GatewayProperties.Retry r = properties.retry();
        return new RetryPolicy(r.maxAttempts(), r.initialBackoff(), r.multiplier(), r.maxBackoff(), r.jitter());
    }

    /** Starts full so a freshly started gateway can retry immediately. */
    @Bean
    public RetryBudget retryBudget(GatewayProperties properties) {
        GatewayProperties.Retry r = properties.retry();
        return new RetryBudget(r.budgetRatio(), r.budgetMaxTokens(), r.budgetMaxTokens());
    }

    @Bean
    public RetryDecider retryDecider() {
        return new RetryDecider();
    }

    @Bean
    public Sleeper retrySleeper() {
        return Sleeper.threadSleep();
    }

    /** ThreadLocalRandom: no contention between request threads, unlike a shared java.util.Random. */
    @Bean
    public DoubleSupplier jitterRandom() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
