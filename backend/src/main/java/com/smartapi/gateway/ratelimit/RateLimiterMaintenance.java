package com.smartapi.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically frees memory held for idle rate-limit keys. */
@Component
public class RateLimiterMaintenance {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterMaintenance.class);

    private final RateLimiter rateLimiter;

    public RateLimiterMaintenance(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evictExpiredKeys() {
        int before = rateLimiter.trackedKeys();
        rateLimiter.evictExpired();
        log.debug("Rate limiter eviction: {} -> {} tracked keys", before, rateLimiter.trackedKeys());
    }
}
