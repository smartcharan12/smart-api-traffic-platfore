package com.smartapi.gateway.repository;

import com.smartapi.gateway.entity.ServiceInstance;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, Long> {

    boolean existsByServiceIdAndHostAndPort(Long serviceId, String host, int port);

    /** Gateway routing: instances in a stable order (round robin relies on it). No join: the caller has the service. */
    List<ServiceInstance> findAllByServiceIdOrderByIdAsc(Long serviceId);

    // JOIN FETCH loads each instance's service in the same SQL query. Without it, reading
    // instance.getService().getName() for N instances would fire N extra queries (the N+1 problem).
    @Query("select i from ServiceInstance i join fetch i.service order by i.service.name, i.id")
    List<ServiceInstance> findAllWithService();

    @Query("select i from ServiceInstance i join fetch i.service where i.service.id = :serviceId order by i.id")
    List<ServiceInstance> findAllByServiceIdWithService(Long serviceId);

    @Query("select i from ServiceInstance i join fetch i.service where i.id = :id")
    Optional<ServiceInstance> findByIdWithService(Long id);
}
