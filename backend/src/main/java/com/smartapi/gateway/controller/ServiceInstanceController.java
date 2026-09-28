package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.CreateServiceInstanceRequest;
import com.smartapi.gateway.dto.ServiceInstanceResponse;
import com.smartapi.gateway.service.ServiceInstanceService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/service-instances")
public class ServiceInstanceController {

    private final ServiceInstanceService instanceService;

    public ServiceInstanceController(ServiceInstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @PostMapping
    public ResponseEntity<ServiceInstanceResponse> create(@Valid @RequestBody CreateServiceInstanceRequest request) {
        ServiceInstanceResponse created = instanceService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code GET /api/service-instances} or {@code GET /api/service-instances?serviceId=1}. */
    @GetMapping
    public List<ServiceInstanceResponse> list(@RequestParam(required = false) Long serviceId) {
        return instanceService.findAll(serviceId);
    }

    @GetMapping("/{id}")
    public ServiceInstanceResponse get(@PathVariable Long id) {
        return instanceService.findById(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        instanceService.delete(id);
    }
}
