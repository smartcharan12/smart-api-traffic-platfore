package com.smartapi.gateway.service;

import com.smartapi.gateway.dto.HealthResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Health of the gateway itself and its dependencies. Redis is added here in Phase 3.
 * (Health of the backend services the gateway routes to is a different concern, handled by the
 * health checker in Phase 3.)
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public SystemHealthService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public HealthResponse check() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("database", databaseStatus());
        String overall = components.values().stream().allMatch(UP::equals) ? UP : DOWN;
        return new HealthResponse(overall, components, Instant.now(clock));
    }

    private String databaseStatus() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return UP;
        } catch (DataAccessException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return DOWN;
        }
    }
}
