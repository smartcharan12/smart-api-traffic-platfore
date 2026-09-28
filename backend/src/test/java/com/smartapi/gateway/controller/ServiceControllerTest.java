package com.smartapi.gateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartapi.gateway.dto.CreateServiceRequest;
import com.smartapi.gateway.dto.ServiceResponse;
import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.service.ServiceRegistryService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Web-layer slice test: starts only MVC infrastructure (controllers, JSON, validation, exception
 * handler), not the database. Verifies HTTP concerns: status codes, headers, JSON shape, validation.
 */
@WebMvcTest(ServiceController.class)
class ServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceRegistryService registryService;

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        when(registryService.register(any(CreateServiceRequest.class))).thenReturn(new ServiceResponse(
                5L, "orders-service", "/orders", null, LoadBalancingStrategyType.ROUND_ROBIN,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "orders-service", "basePath": "/orders"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/services/5"))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.loadBalancingStrategy").value("ROUND_ROBIN"));
    }

    @Test
    void createWithInvalidFieldsReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Orders Service!", "basePath": "orders"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.basePath").exists());
    }

    @Test
    void createWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithUnknownStrategyReturns400() throws Exception {
        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "orders-service", "basePath": "/orders", "loadBalancingStrategy": "RANDOM"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMissingServiceReturns404ProblemDetail() throws Exception {
        when(registryService.findById(42L)).thenThrow(new ResourceNotFoundException("Service", 42L));

        mockMvc.perform(get("/api/services/42"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.detail").value("Service with id 42 not found"));
    }

    @Test
    void nonNumericIdReturns400Not500() throws Exception {
        mockMvc.perform(get("/api/services/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void databaseOutageReturns503Not500() throws Exception {
        when(registryService.findAll()).thenThrow(new CannotCreateTransactionException("Connection refused"));

        mockMvc.perform(get("/api/services"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The database is temporarily unavailable"));
    }
}
