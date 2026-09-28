package com.smartapi.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One gateway request. Written on the hot path from Phase 2 onward, so two deliberate choices:
 * <ul>
 *   <li>Sequence ids with allocationSize 50 (matches {@code INCREMENT BY 50} in V1) so Hibernate
 *       fetches ids in blocks and can batch inserts.</li>
 *   <li>Foreign keys are plain {@code Long} columns rather than {@code @ManyToOne} associations,
 *       so writing a log never has to load a User/ApiService/ServiceInstance entity first.</li>
 * </ul>
 */
@Entity
@Table(name = "request_logs")
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "request_logs_seq")
    @SequenceGenerator(name = "request_logs_seq", sequenceName = "request_logs_seq", allocationSize = 50)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "service_id")
    private Long serviceId;

    @Column(name = "instance_id")
    private Long instanceId;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(nullable = false, length = 2048)
    private String path;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RequestLog() {
    }

    public RequestLog(UUID requestId, Long userId, Long serviceId, Long instanceId, String httpMethod,
                      String path, String clientIp, int statusCode, int latencyMs, int retryCount,
                      String failureReason, Instant createdAt) {
        this.requestId = requestId;
        this.userId = userId;
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.httpMethod = httpMethod;
        this.path = path;
        this.clientIp = clientIp;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.retryCount = retryCount;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public String getClientIp() {
        return clientIp;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
