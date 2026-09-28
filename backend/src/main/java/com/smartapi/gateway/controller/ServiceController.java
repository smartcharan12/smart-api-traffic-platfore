package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.CreateServiceRequest;
import com.smartapi.gateway.dto.ServiceResponse;
import com.smartapi.gateway.service.ServiceRegistryService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * HTTP layer only: parse/validate input, call the service, choose the status code.
 * No business rules and no repository access here.
 */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceRegistryService registryService;

    public ServiceController(ServiceRegistryService registryService) {
        this.registryService = registryService;
    }

    /** 201 Created with a Location header pointing at the new resource. */
    @PostMapping
    public ResponseEntity<ServiceResponse> create(@Valid @RequestBody CreateServiceRequest request) {
        ServiceResponse created = registryService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<ServiceResponse> list() {
        return registryService.findAll();
    }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable Long id) {
        return registryService.findById(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        registryService.delete(id);
    }
}
