package com.smartapi.gateway.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the system clock as a bean. Code that needs "now" asks for a Clock instead of calling
 * Instant.now() directly, so tests can inject a fixed clock. This matters a lot in Phase 2, where
 * rate-limit windows and token refills are all time-based.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
