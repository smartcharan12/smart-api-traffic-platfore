package com.smartapi.gateway.metrics;

import com.smartapi.gateway.cache.ResponseCache;
import com.smartapi.gateway.dto.InstanceHealthView;
import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.health.InstanceHealthReporter;
import com.smartapi.gateway.logging.RequestLogWriter;
import com.smartapi.gateway.ratelimit.FallbackRateLimiter;
import com.smartapi.gateway.ratelimit.RateLimiter;
import com.smartapi.gateway.redis.RedisGuard;
import com.smartapi.gateway.resilience.CircuitBreakerRegistry;
import com.smartapi.gateway.resilience.CircuitState;
import com.smartapi.gateway.resilience.RetryBudget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * All gateway metrics, exposed by Spring Actuator at /actuator/prometheus. Names below are the
 * Prometheus names (Micrometer converts dots to underscores and adds unit suffixes).
 *
 * <pre>
 * gateway_requests_seconds{service,method,status}      histogram: rate() = RPS; sum/count = avg latency;
 *                                                       histogram_quantile(0.95|0.99) = p95 / p99
 * gateway_rate_limit_rejections_total                   429s
 * gateway_retries_total{service,reason}                 retries performed
 * gateway_circuit_breaker_transitions_total{upstream,to}  e.g. to="OPEN" counts circuit openings
 * gateway_circuit_breaker_state{upstream}               0 closed, 1 half-open, 2 open
 * gateway_cache_requests_total{result}                  HIT / MISS
 * gateway_instance_up{service,upstream}                 1 healthy, 0.5 degraded, 0 down (UNKNOWN: -1)
 * gateway_instance_uptime_seconds{service,upstream}     time since the instance last came up
 * gateway_instance_active_connections{service,upstream} in-flight requests from this gateway
 * gateway_instance_probe_latency_seconds{...}           last health-probe latency
 * gateway_request_log_dropped_total, gateway_redis_*, gateway_retry_budget_tokens
 * </pre>
 *
 * <p>The backend address label is called {@code upstream}, not {@code instance}: Prometheus reserves
 * {@code instance} for the scrape target (the gateway itself) and would rename ours to
 * {@code exported_instance}. (Found when the first Grafana screenshot labelled every backend
 * "localhost:8080".)
 *
 * <p>Label cardinality is bounded on purpose: {@code service} is a registered service name or
 * "unresolved", never the raw path, so random URLs can't create unlimited time series.
 */
@Component
public class GatewayMetrics {

    private static final Logger log = LoggerFactory.getLogger(GatewayMetrics.class);
    public static final String UNRESOLVED = "unresolved";

    private final MeterRegistry registry;
    private final Counter rateLimitRejections;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final CircuitBreakerRegistry breakers;
    private final InstanceHealthReporter healthReporter;
    private final MultiGauge instanceUp;
    private final MultiGauge instanceUptime;
    private final MultiGauge activeConnections;
    private final MultiGauge probeLatency;
    private final MultiGauge circuitState;

    public GatewayMetrics(MeterRegistry registry, CircuitBreakerRegistry breakers, InstanceHealthReporter healthReporter,
                          RequestLogWriter requestLogWriter, RedisGuard redisGuard, RateLimiter rateLimiter,
                          RetryBudget retryBudget) {
        this.registry = registry;
        this.breakers = breakers;
        this.healthReporter = healthReporter;
        this.rateLimitRejections = Counter.builder("gateway.rate_limit.rejections")
                .description("Requests rejected with 429").register(registry);
        this.cacheHits = Counter.builder("gateway.cache.requests").tag("result", "HIT").register(registry);
        this.cacheMisses = Counter.builder("gateway.cache.requests").tag("result", "MISS").register(registry);

        breakers.addListener(t -> Counter.builder("gateway.circuit_breaker.transitions")
                .tags("upstream", t.name(), "to", t.to().name()).register(registry).increment());

        FunctionCounter.builder("gateway.request_log.dropped", requestLogWriter, RequestLogWriter::dropped)
                .description("Request log rows dropped (queue full or database error)").register(registry);
        FunctionCounter.builder("gateway.request_log.written", requestLogWriter, RequestLogWriter::written)
                .register(registry);
        Gauge.builder("gateway.request_log.queued", requestLogWriter, RequestLogWriter::queued).register(registry);
        FunctionCounter.builder("gateway.redis.failures", redisGuard, RedisGuard::failures).register(registry);
        FunctionCounter.builder("gateway.redis.skipped", redisGuard, RedisGuard::skipped)
                .description("Redis calls skipped because Redis' circuit is open").register(registry);
        Gauge.builder("gateway.redis.available", redisGuard, g -> g.state() == CircuitState.OPEN ? 0 : 1)
                .register(registry);
        if (rateLimiter instanceof FallbackRateLimiter fallback) {
            FunctionCounter.builder("gateway.rate_limit.local_fallback_decisions", fallback,
                    FallbackRateLimiter::fallbackDecisions).register(registry);
        }
        Gauge.builder("gateway.retry_budget.tokens", retryBudget, RetryBudget::available).register(registry);

        this.instanceUp = MultiGauge.builder("gateway.instance.up").register(registry);
        this.instanceUptime = MultiGauge.builder("gateway.instance.uptime").baseUnit("seconds").register(registry);
        this.activeConnections = MultiGauge.builder("gateway.instance.active_connections").register(registry);
        this.probeLatency = MultiGauge.builder("gateway.instance.probe_latency").baseUnit("seconds").register(registry);
        this.circuitState = MultiGauge.builder("gateway.circuit_breaker.state").register(registry);
    }

    /** One per gateway request, whatever its outcome. */
    public void recordRequest(String service, String method, int status, long durationNanos) {
        Timer.builder("gateway.requests")
                .description("Gateway requests, end to end")
                .tags("service", service, "method", method, "status", String.valueOf(status))
                // Fixed buckets (SLO boundaries) instead of Micrometer's ~70 default histogram buckets
                // keep the number of time series small; p95/p99 are computed in Prometheus.
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofMillis(2500), Duration.ofSeconds(5))
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void rateLimited() {
        rateLimitRejections.increment();
    }

    public void retry(String service, String reason) {
        Counter.builder("gateway.retries").tags("service", service, "reason", reason).register(registry).increment();
    }

    public void cache(boolean hit) {
        (hit ? cacheHits : cacheMisses).increment();
    }

    /**
     * Refreshes per-instance gauges from the health report. MultiGauge with overwrite drops rows for
     * instances that no longer exist, so deleted instances don't linger as stale series.
     */
    @Scheduled(initialDelay = 1000, fixedDelay = 5000)
    public void refreshInstanceGauges() {
        try {
            List<InstanceHealthView> instances = healthReporter.report();
            instanceUp.register(instances.stream().map(i -> MultiGauge.Row.of(tags(i), upValue(i.status()))).toList(), true);
            instanceUptime.register(instances.stream().filter(i -> i.uptimeSeconds() != null)
                    .map(i -> MultiGauge.Row.of(tags(i), i.uptimeSeconds())).toList(), true);
            activeConnections.register(instances.stream()
                    .map(i -> MultiGauge.Row.of(tags(i), i.activeConnections())).toList(), true);
            probeLatency.register(instances.stream().filter(i -> i.latencyMs() != null)
                    .map(i -> MultiGauge.Row.of(tags(i), i.latencyMs() / 1000.0)).toList(), true);
            circuitState.register(breakers.snapshots().stream()
                    .map(s -> MultiGauge.Row.of(Tags.of("upstream", s.name()), s.state().level())).toList(), true);
        } catch (RuntimeException e) {
            log.debug("Could not refresh instance gauges: {}", e.getMessage());
        }
    }

    private static Tags tags(InstanceHealthView i) {
        return Tags.of("service", i.serviceName(), "upstream", i.address());
    }

    private static double upValue(InstanceStatus status) {
        return switch (status) {
            case HEALTHY -> 1;
            case DEGRADED -> 0.5;
            case DOWN -> 0;
            case UNKNOWN -> -1;
        };
    }
}
