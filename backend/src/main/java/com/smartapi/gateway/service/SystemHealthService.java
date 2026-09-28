package com.smartapi.gateway.service;

import com.smartapi.gateway.dto.HealthResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private static final String DEGRADED = "DEGRADED";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public SystemHealthService(JdbcTemplate jdbcTemplate, StringRedisTemplate redis, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.clock = clock;
    }

    public HealthResponse check() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("database", databaseStatus());
        components.put("redis", redisStatus());
        // The database is required (registry, logs); Redis is optional (local fallbacks exist).
        String overall = DOWN.equals(components.get("database")) ? DOWN
                : components.values().stream().allMatch(UP::equals) ? UP : DEGRADED;
        return new HealthResponse(overall, components, Instant.now(clock));
    }

    /** Pings Redis directly (not through RedisGuard) so the report reflects Redis right now. */
    private String redisStatus() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equals(pong) ? UP : DOWN;
        } catch (RuntimeException e) {
            return DOWN;
        }
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
