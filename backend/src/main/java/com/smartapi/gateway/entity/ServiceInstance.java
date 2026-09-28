package com.smartapi.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** One running copy of a service, reachable at {@code host:port}. */
@Entity
@Table(name = "service_instances")
public class ServiceInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: loading an instance does not load its service unless we ask for it (or JOIN FETCH it).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ApiService service;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(nullable = false)
    private int weight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InstanceStatus status;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceInstance() {
    }

    public ServiceInstance(ApiService service, String host, int port, int weight) {
        this.service = service;
        this.host = host;
        this.port = port;
        this.weight = weight;
        this.status = InstanceStatus.UNKNOWN;
    }

    /** Manual status override (PATCH endpoint). Phase 3's health checker will call this automatically. */
    public void changeStatus(InstanceStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public ApiService getService() {
        return service;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getWeight() {
        return weight;
    }

    public InstanceStatus getStatus() {
        return status;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
