package com.smartapi.gateway.service;

import com.smartapi.gateway.dto.CreateServiceInstanceRequest;
import com.smartapi.gateway.dto.ServiceInstanceResponse;
import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.entity.ServiceInstance;
import com.smartapi.gateway.exception.DuplicateResourceException;
import com.smartapi.gateway.exception.InvalidRequestException;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.repository.ApiServiceRepository;
import com.smartapi.gateway.repository.ServiceInstanceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for registering and listing instances of a service. */
@Service
public class ServiceInstanceService {

    private static final int DEFAULT_WEIGHT = 1;

    private final ServiceInstanceRepository instanceRepository;
    private final ApiServiceRepository serviceRepository;

    public ServiceInstanceService(ServiceInstanceRepository instanceRepository,
                                  ApiServiceRepository serviceRepository) {
        this.instanceRepository = instanceRepository;
        this.serviceRepository = serviceRepository;
    }

    @Transactional
    public ServiceInstanceResponse register(CreateServiceInstanceRequest request) {
        ApiService service = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new InvalidRequestException(
                        "serviceId %d does not refer to a registered service".formatted(request.serviceId())));
        String host = request.host().toLowerCase();
        if (instanceRepository.existsByServiceIdAndHostAndPort(service.getId(), host, request.port())) {
            throw new DuplicateResourceException("Instance %s:%d is already registered for service '%s'"
                    .formatted(host, request.port(), service.getName()));
        }
        int weight = request.weight() != null ? request.weight() : DEFAULT_WEIGHT;
        ServiceInstance saved = instanceRepository.save(new ServiceInstance(service, host, request.port(), weight));
        return ServiceInstanceResponse.from(saved);
    }

    /** @param serviceId optional filter; null returns instances of every service */
    @Transactional(readOnly = true)
    public List<ServiceInstanceResponse> findAll(Long serviceId) {
        List<ServiceInstance> instances = serviceId == null
                ? instanceRepository.findAllWithService()
                : instanceRepository.findAllByServiceIdWithService(serviceId);
        return instances.stream().map(ServiceInstanceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ServiceInstanceResponse findById(Long id) {
        return instanceRepository.findByIdWithService(id)
                .map(ServiceInstanceResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Service instance", id));
    }

    @Transactional
    public void delete(Long id) {
        if (!instanceRepository.existsById(id)) {
            throw new ResourceNotFoundException("Service instance", id);
        }
        instanceRepository.deleteById(id);
    }
}
