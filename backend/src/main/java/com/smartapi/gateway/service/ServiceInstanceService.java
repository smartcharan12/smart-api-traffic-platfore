package com.smartapi.gateway.service;

import com.smartapi.gateway.dto.CreateServiceInstanceRequest;
import com.smartapi.gateway.dto.ServiceInstanceResponse;
import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.entity.ServiceInstance;
import com.smartapi.gateway.exception.DuplicateResourceException;
import com.smartapi.gateway.exception.InvalidRequestException;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.repository.ApiServiceRepository;
import com.smartapi.gateway.repository.ServiceInstanceRepository;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for registering and listing instances of a service. */
@Service
public class ServiceInstanceService {

    private static final int DEFAULT_WEIGHT = 1;

    private final ServiceInstanceRepository instanceRepository;
    private final ApiServiceRepository serviceRepository;

    private final ApplicationEventPublisher events;

    public ServiceInstanceService(ServiceInstanceRepository instanceRepository,
                                  ApiServiceRepository serviceRepository,
                                  ApplicationEventPublisher events) {
        this.instanceRepository = instanceRepository;
        this.serviceRepository = serviceRepository;
        this.events = events;
    }

    @Transactional
    public ServiceInstanceResponse register(CreateServiceInstanceRequest request) {
        ApiService service = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new InvalidRequestException(
                        "serviceId %d does not refer to a registered service".formatted(request.serviceId())));
        String host = request.host().toLowerCase();
        BackendHostPolicy.check(host);
        if (instanceRepository.existsByServiceIdAndHostAndPort(service.getId(), host, request.port())) {
            throw new DuplicateResourceException("Instance %s:%d is already registered for service '%s'"
                    .formatted(host, request.port(), service.getName()));
        }
        int weight = request.weight() != null ? request.weight() : DEFAULT_WEIGHT;
        ServiceInstance saved = instanceRepository.save(new ServiceInstance(service, host, request.port(), weight));
        events.publishEvent(new RegistryChangedEvent("instance registered: " + host + ":" + request.port()));
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

    /**
     * No explicit save(): the entity is "managed" inside the transaction, so Hibernate detects the
     * changed field (dirty checking) and issues the UPDATE at commit.
     */
    @Transactional
    public ServiceInstanceResponse updateStatus(Long id, InstanceStatus status) {
        ServiceInstance instance = instanceRepository.findByIdWithService(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service instance", id));
        instance.changeStatus(status);
        events.publishEvent(new RegistryChangedEvent("instance " + id + " status: " + status));
        return ServiceInstanceResponse.from(instance);
    }

    /** Heartbeats don't change routing, so no RegistryChangedEvent is published. */
    @Transactional
    public void recordHeartbeats(java.util.Collection<Long> instanceIds, java.time.Instant at) {
        if (!instanceIds.isEmpty()) {
            instanceRepository.updateHeartbeats(instanceIds, at);
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!instanceRepository.existsById(id)) {
            throw new ResourceNotFoundException("Service instance", id);
        }
        instanceRepository.deleteById(id);
        events.publishEvent(new RegistryChangedEvent("instance deleted: " + id));
    }
}
