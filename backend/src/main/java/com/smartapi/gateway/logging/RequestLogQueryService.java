package com.smartapi.gateway.logging;

import com.smartapi.gateway.dto.PageResponse;
import com.smartapi.gateway.dto.RequestLogResponse;
import com.smartapi.gateway.entity.ApiService;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.repository.ApiServiceRepository;
import com.smartapi.gateway.repository.RequestLogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestLogQueryService {

    public static final int MAX_PAGE_SIZE = 200;

    private final RequestLogRepository logs;
    private final ApiServiceRepository services;

    public RequestLogQueryService(RequestLogRepository logs, ApiServiceRepository services) {
        this.logs = logs;
        this.services = services;
    }

    /**
     * @param statusClass optional 1-5 (e.g. 5 = all 5xx); ignored when {@code status} is given
     */
    @Transactional(readOnly = true)
    public PageResponse<RequestLogResponse> search(Long serviceId, Integer status, Integer statusClass,
                                                   String clientId, int page, int size) {
        Integer from = status != null ? status : statusClass != null ? statusClass * 100 : null;
        Integer to = status != null ? status : statusClass != null ? statusClass * 100 + 99 : null;
        // Service names are looked up once per page, not once per row (no N+1).
        Map<Long, String> names = serviceNames();
        return PageResponse.of(logs.search(serviceId, from, to, clientId,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)))
                .map(l -> RequestLogResponse.from(l, names.get(l.getServiceId()))));
    }

    @Transactional(readOnly = true)
    public List<RequestLogResponse> byRequestId(UUID requestId) {
        Map<Long, String> names = serviceNames();
        List<RequestLogResponse> found = logs.findByRequestId(requestId).stream()
                .map(l -> RequestLogResponse.from(l, names.get(l.getServiceId()))).toList();
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Request log", requestId);
        }
        return found;
    }

    private Map<Long, String> serviceNames() {
        return services.findAll().stream().collect(Collectors.toMap(ApiService::getId, ApiService::getName));
    }
}
