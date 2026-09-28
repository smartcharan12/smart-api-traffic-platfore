package com.smartapi.gateway.repository;

import com.smartapi.gateway.entity.ApiService;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data generates the implementation at startup. Method names are parsed into queries:
 * {@code existsByName} becomes {@code select count(*) > 0 from services where name = ?}.
 */
public interface ApiServiceRepository extends JpaRepository<ApiService, Long> {

    boolean existsByName(String name);

    boolean existsByBasePath(String basePath);

    /** Gateway routing: which service owns this base path? Backed by the uq_services_base_path index. */
    Optional<ApiService> findByBasePath(String basePath);
}
