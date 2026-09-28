package com.smartapi.gateway.service;

import com.smartapi.gateway.dto.CreateServiceRequest;
import com.smartapi.gateway.dto.ServiceResponse;
import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import com.smartapi.gateway.exception.DuplicateResourceException;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.repository.ApiServiceRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for registering and managing logical services. */
@Service
public class ServiceRegistryService {

    private final ApiServiceRepository serviceRepository;

    // Constructor injection: Spring sees the single constructor and passes in the repository bean.
    // The field can be final, and a unit test can call `new ServiceRegistryService(mockRepo)`.
    public ServiceRegistryService(ApiServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    /**
     * The exists-checks give a clear error message in the common case. They are NOT enough on
     * their own: two concurrent requests can both pass the check before either inserts. The
     * UNIQUE constraints in the database are the real guarantee; GlobalExceptionHandler turns the
     * resulting DataIntegrityViolationException into a 409 as well.
     */
    @Transactional
    public ServiceResponse register(CreateServiceRequest request) {
        if (serviceRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("Service name '%s' is already registered".formatted(request.name()));
        }
        if (serviceRepository.existsByBasePath(request.basePath())) {
            throw new DuplicateResourceException("Base path '%s' is already in use".formatted(request.basePath()));
        }
        LoadBalancingStrategyType strategy = request.loadBalancingStrategy() != null
                ? request.loadBalancingStrategy()
                : LoadBalancingStrategyType.ROUND_ROBIN;
        ApiService saved = serviceRepository.save(
                new ApiService(request.name(), request.basePath(), request.description(), strategy));
        return ServiceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> findAll() {
        return serviceRepository.findAll(Sort.by("name")).stream()
                .map(ServiceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceResponse findById(Long id) {
        return serviceRepository.findById(id)
                .map(ServiceResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Service", id));
    }

    /** Deleting a service also deletes its instances (ON DELETE CASCADE in the schema). */
    @Transactional
    public void delete(Long id) {
        if (!serviceRepository.existsById(id)) {
            throw new ResourceNotFoundException("Service", id);
        }
        serviceRepository.deleteById(id);
    }
}
