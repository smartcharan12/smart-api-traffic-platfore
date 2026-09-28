package com.smartapi.gateway.logging;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.repository.RequestLogRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Deletes request logs older than the retention period, hourly. Idempotent, so every gateway may run it. */
@Component
public class RequestLogRetention {

    private static final Logger log = LoggerFactory.getLogger(RequestLogRetention.class);

    private final RequestLogRepository repository;
    private final Clock clock;
    private final Duration retention;

    public RequestLogRetention(RequestLogRepository repository, Clock clock, GatewayProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.retention = properties.requestLog().retention();
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    @Transactional
    public int purge() {
        int deleted = repository.deleteOlderThan(clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("Deleted {} request logs older than {}", deleted, retention);
        }
        return deleted;
    }
}
