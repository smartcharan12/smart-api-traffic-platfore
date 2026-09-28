package com.smartapi.gateway.logging;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Traffic statistics computed in PostgreSQL from request_logs, so they cover ALL gateway instances
 * (they all write to the same table), unlike in-process counters. Percentiles use percentile_cont.
 * These are for the dashboard; Prometheus metrics (per gateway, real time) are the monitoring source.
 */
@Service
public class TrafficStatsService {

    public static final int MAX_WINDOW_MINUTES = 24 * 60;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TrafficStatsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Map<String, Object> summary(int minutes) {
        Timestamp since = since(minutes);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*)                                          AS total,
                       count(*) FILTER (WHERE status_code >= 500)       AS server_errors,
                       count(*) FILTER (WHERE status_code = 429)        AS rate_limited,
                       count(*) FILTER (WHERE retry_count > 0)          AS retried_requests,
                       coalesce(sum(retry_count), 0)                    AS retries,
                       round(avg(latency_ms)::numeric, 1)               AS avg_latency_ms,
                       percentile_cont(0.5)  WITHIN GROUP (ORDER BY latency_ms) AS p50_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95_ms,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) AS p99_ms
                FROM request_logs WHERE created_at >= ?
                """, since);
        List<Map<String, Object>> perMinute = jdbc.queryForList("""
                SELECT date_trunc('minute', created_at)               AS minute,
                       count(*)                                         AS total,
                       count(*) FILTER (WHERE status_code >= 500)      AS server_errors,
                       count(*) FILTER (WHERE status_code = 429)       AS rate_limited,
                       round(avg(latency_ms)::numeric, 1)              AS avg_latency_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95_ms
                FROM request_logs WHERE created_at >= ?
                GROUP BY 1 ORDER BY 1
                """, since);
        List<Map<String, Object>> byService = jdbc.queryForList("""
                SELECT coalesce(s.name, '(no service)')                 AS service,
                       count(*)                                         AS total,
                       count(*) FILTER (WHERE l.status_code >= 500)    AS server_errors,
                       count(*) FILTER (WHERE l.status_code = 429)     AS rate_limited,
                       round(avg(l.latency_ms)::numeric, 1)            AS avg_latency_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY l.latency_ms) AS p95_ms
                FROM request_logs l LEFT JOIN services s ON s.id = l.service_id
                WHERE l.created_at >= ?
                GROUP BY 1 ORDER BY 2 DESC
                """, since);
        return Map.of("windowMinutes", minutes, "totals", totals, "perMinute", perMinute, "byService", byService);
    }

    public Map<String, Object> failures(int minutes) {
        Timestamp since = since(minutes);
        List<Map<String, Object>> grouped = jdbc.queryForList("""
                SELECT coalesce(s.name, '(no service)')  AS service,
                       l.status_code,
                       coalesce(l.failure_reason, '')     AS failure_reason,
                       count(*)                           AS occurrences,
                       max(l.created_at)                  AS last_seen
                FROM request_logs l LEFT JOIN services s ON s.id = l.service_id
                WHERE l.created_at >= ? AND (l.status_code >= 500 OR l.retry_count > 0 OR l.failure_reason IS NOT NULL)
                GROUP BY 1, 2, 3 ORDER BY occurrences DESC LIMIT 50
                """, since);
        return Map.of("windowMinutes", minutes, "groups", grouped);
    }

    private Timestamp since(int minutes) {
        int bounded = Math.min(Math.max(1, minutes), MAX_WINDOW_MINUTES);
        return Timestamp.from(clock.instant().minus(Duration.ofMinutes(bounded)));
    }

    Instant now() {
        return clock.instant();
    }
}
