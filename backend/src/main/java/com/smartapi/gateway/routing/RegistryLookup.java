package com.smartapi.gateway.routing;

import com.smartapi.gateway.loadbalancer.BackendInstance;
import com.smartapi.gateway.repository.ApiServiceRepository;
import com.smartapi.gateway.repository.ServiceInstanceRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a route's service and instances from PostgreSQL (2 queries, one read-only transaction).
 *
 * <p>Deliberately a separate bean from {@link ServiceDiscovery}: {@code @Transactional} works through a
 * Spring proxy around the bean, and a call from inside the same class (this.load()) bypasses the
 * proxy. Keeping the cache in ServiceDiscovery and the transaction here means a cache hit never
 * opens a transaction or borrows a database connection.
 *
 * <p>The transaction ends when this method returns, before the gateway makes the slow network call
 * to the backend. Holding a pooled DB connection during a remote call would let a slow backend
 * exhaust the pool and take the management API down with it.
 */
@Component
public class RegistryLookup {

    private final ApiServiceRepository serviceRepository;
    private final ServiceInstanceRepository instanceRepository;

    public RegistryLookup(ApiServiceRepository serviceRepository, ServiceInstanceRepository instanceRepository) {
        this.serviceRepository = serviceRepository;
        this.instanceRepository = instanceRepository;
    }

    @Transactional(readOnly = true)
    public Optional<DiscoveredService> load(String basePath) {
        return serviceRepository.findByBasePath(basePath).map(service -> new DiscoveredService(
                service.getId(),
                service.getName(),
                service.getBasePath(),
                service.getLoadBalancingStrategy(),
                instanceRepository.findAllByServiceIdOrderByIdAsc(service.getId()).stream()
                        .map(instance -> BackendInstance.from(instance, service.getId()))
                        .toList()));
    }
}
