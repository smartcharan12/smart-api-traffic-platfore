package com.smartapi.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartapi.gateway.dto.CreateServiceRequest;
import com.smartapi.gateway.dto.ServiceResponse;
import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import com.smartapi.gateway.exception.DuplicateResourceException;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.repository.ApiServiceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pure unit test: no Spring context, no database. The repository is a Mockito mock. */
@ExtendWith(MockitoExtension.class)
class ServiceRegistryServiceTest {

    @Mock
    private ApiServiceRepository repository;

    @InjectMocks
    private ServiceRegistryService registryService;

    @Test
    void registerDefaultsToRoundRobinWhenNoStrategyGiven() {
        when(repository.save(any(ApiService.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceResponse response = registryService.register(
                new CreateServiceRequest("orders-service", "/orders", "Orders", null));

        ArgumentCaptor<ApiService> saved = ArgumentCaptor.forClass(ApiService.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getLoadBalancingStrategy()).isEqualTo(LoadBalancingStrategyType.ROUND_ROBIN);
        assertThat(response.name()).isEqualTo("orders-service");
        assertThat(response.basePath()).isEqualTo("/orders");
    }

    @Test
    void registerRejectsDuplicateNameWithoutSaving() {
        when(repository.existsByName("orders-service")).thenReturn(true);

        assertThatThrownBy(() -> registryService.register(
                new CreateServiceRequest("orders-service", "/orders", null, null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("orders-service");
        verify(repository, never()).save(any());
    }

    @Test
    void registerRejectsDuplicateBasePath() {
        when(repository.existsByBasePath("/orders")).thenReturn(true);

        assertThatThrownBy(() -> registryService.register(
                new CreateServiceRequest("orders-v2", "/orders", null, null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("/orders");
        verify(repository, never()).save(any());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registryService.findById(42L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Service with id 42 not found");
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(repository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> registryService.delete(7L)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }
}
