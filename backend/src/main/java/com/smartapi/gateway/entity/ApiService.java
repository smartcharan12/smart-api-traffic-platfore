package com.smartapi.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A logical backend service registered with the gateway, e.g. {@code orders-service} at {@code /orders}.
 * Named ApiService (not Service) to avoid clashing with Spring's {@code @Service} annotation.
 *
 * <p>The relationship to {@link ServiceInstance} is mapped only on the instance side (unidirectional
 * {@code @ManyToOne}). A {@code List<ServiceInstance>} here would be convenient but invites accidental
 * lazy loading and N+1 queries; we query instances explicitly through their repository instead.
 */
@Entity
@Table(name = "services")
public class ApiService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "base_path", nullable = false, length = 100)
    private String basePath;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "load_balancing_strategy", nullable = false, length = 30)
    private LoadBalancingStrategyType loadBalancingStrategy;

    @Column(name = "health_check_path", nullable = false, length = 200)
    private String healthCheckPath = "/health";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA, which instantiates entities reflectively. Not for application code. */
    protected ApiService() {
    }

    public ApiService(String name, String basePath, String description,
                      LoadBalancingStrategyType loadBalancingStrategy) {
        this.name = name;
        this.basePath = basePath;
        this.description = description;
        this.loadBalancingStrategy = loadBalancingStrategy;
    }

    public ApiService(String name, String basePath, String description,
                      LoadBalancingStrategyType loadBalancingStrategy, String healthCheckPath) {
        this(name, basePath, description, loadBalancingStrategy);
        if (healthCheckPath != null) {
            this.healthCheckPath = healthCheckPath;
        }
    }

    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getDescription() {
        return description;
    }

    public LoadBalancingStrategyType getLoadBalancingStrategy() {
        return loadBalancingStrategy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
