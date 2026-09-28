package com.smartapi.gateway.routing;

import com.smartapi.gateway.ratelimit.RateLimitDecision;
import com.smartapi.gateway.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects facts about one gateway request as it moves through the pipeline, then writes exactly one
 * log line in key=value form, e.g.
 * <pre>
 * requestId=7c3e... method=GET path=/gateway/orders/orders/1 client=127.0.0.1 user=- service=orders-service
 * instance=localhost:9002 attempts=1 status=200 latencyMs=4 rateLimit=ALLOWED remaining=97 error=-
 * </pre>
 * One line per request (not one per step) keeps logs small; key=value is greppable and trivially parsed
 * by log tools. Not thread-safe by design: each instance belongs to one request thread.
 */
final class GatewayAccessLog {

    private static final Logger log = LoggerFactory.getLogger("gateway.access");

    private final GatewayRequest request;
    private final long startNanos = System.nanoTime(); // monotonic: immune to wall-clock adjustments
    private String service = "-";
    private Long serviceId;
    private Long instanceId;
    private String instance = "-";
    private int attempts;
    private int retries;
    private final java.util.List<String> failures = new java.util.ArrayList<>();
    private int status;
    private String rateLimit = "-";
    private String remaining = "-";
    private String error = "-";
    private String cache = "-";

    GatewayAccessLog(GatewayRequest request) {
        this.request = request;
    }

    void service(String name) {
        this.service = name;
    }

    void attempt(String address) {
        this.instance = address;
        this.attempts++;
    }

    void ids(Long serviceId, Long instanceId) {
        if (serviceId != null) {
            this.serviceId = serviceId;
        }
        if (instanceId != null) {
            this.instanceId = instanceId;
        }
    }

    /** The same facts as the log line, as a row for request_logs. */
    com.smartapi.gateway.entity.RequestLog toEntity(java.time.Instant at) {
        String requestId = RequestIdFilter.currentRequestId();
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(requestId);
        } catch (RuntimeException e) {
            // Client-supplied ids aren't always UUIDs; derive a stable UUID so the column type holds.
            uuid = java.util.UUID.nameUUIDFromBytes(String.valueOf(requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String reason = !failures.isEmpty() ? String.join(",", failures) : ("-".equals(error) ? null : error);
        if (reason != null && reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        String path = request.path().length() > 2048 ? request.path().substring(0, 2048) : request.path();
        return new com.smartapi.gateway.entity.RequestLog(uuid, null, serviceId, instanceId, request.method(), path,
                request.clientIp(), status, (int) ((System.nanoTime() - startNanos) / 1_000_000), retries, reason, at)
                .withClientId(request.userId());
    }

    void cache(String result) {
        this.cache = result;
    }

    void retry() {
        this.retries++;
    }

    void failure(String failure) {
        this.failures.add(failure);
    }

    /** Registered service name, or "unresolved" (bounded metric label; never the raw path). */
    String serviceLabel() {
        return "-".equals(service) ? com.smartapi.gateway.metrics.GatewayMetrics.UNRESOLVED : service;
    }

    int status() {
        return status;
    }

    long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    int attempts() {
        return attempts;
    }

    int retries() {
        return retries;
    }

    void status(int status) {
        this.status = status;
    }

    void rateLimit(RateLimitDecision decision) {
        if (!decision.isLimited()) {
            this.rateLimit = "DISABLED";
        } else {
            this.rateLimit = decision.allowed() ? "ALLOWED" : "REJECTED";
            this.remaining = String.valueOf(decision.remaining());
        }
    }

    void error(String error) {
        this.error = error;
    }

    void write() {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        String line = "requestId=%s method=%s path=%s client=%s user=%s service=%s instance=%s attempts=%d "
                + "retries=%d status=%d latencyMs=%d rateLimit=%s remaining=%s cache=%s failures=%s error=%s";
        Object[] args = {RequestIdFilter.currentRequestId(), request.method(), request.path(), request.clientIp(),
                request.userId() == null ? "-" : request.userId(), service, instance, attempts, retries, status,
                latencyMs, rateLimit, remaining, cache, failures.isEmpty() ? "-" : String.join(",", failures), error};
        if (status >= 500) {
            log.warn(line.formatted(args));
        } else {
            log.info(line.formatted(args));
        }
    }
}
