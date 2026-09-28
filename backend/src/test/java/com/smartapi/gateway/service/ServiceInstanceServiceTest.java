package com.smartapi.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartapi.gateway.dto.CreateServiceInstanceRequest;
import com.smartapi.gateway.dto.ServiceInstanceResponse;
import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import com.smartapi.gateway.entity.ServiceInstance;
import com.smartapi.gateway.exception.DuplicateResourceException;
import com.smartapi.gateway.exception.InvalidRequestException;
import com.smartapi.gateway.repository.ApiServiceRepository;
import com.smartapi.gateway.repository.ServiceInstanceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ServiceInstanceServiceTest {

    @Mock
    private ServiceInstanceRepository instanceRepository;

    @Mock
    private ApiServiceRepository serviceRepository;

    @InjectMocks
    private ServiceInstanceService instanceService;

    private static ApiService ordersService() {
        ApiService service = new ApiService("orders-service", "/orders", null, LoadBalancingStrategyType.ROUND_ROBIN);
        ReflectionTestUtils.setField(service, "id", 1L); // the database would normally assign this
        return service;
    }

    @Test
    void registerRejectsUnknownService() {
        when(serviceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> instanceService.register(
                new CreateServiceInstanceRequest(99L, "localhost", 9001, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("99");
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void registerRejectsDuplicateEndpoint() {
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(ordersService()));
        when(instanceRepository.existsByServiceIdAndHostAndPort(1L, "localhost", 9001)).thenReturn(true);

        assertThatThrownBy(() -> instanceService.register(
                new CreateServiceInstanceRequest(1L, "localhost", 9001, null)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void registerNormalisesHostAndAppliesDefaults() {
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(ordersService()));
        when(instanceRepository.save(any(ServiceInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceInstanceResponse response = instanceService.register(
                new CreateServiceInstanceRequest(1L, "LocalHost", 9001, null));

        assertThat(response.host()).isEqualTo("localhost");
        assertThat(response.weight()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(InstanceStatus.UNKNOWN);
        assertThat(response.serviceName()).isEqualTo("orders-service");
    }
}
