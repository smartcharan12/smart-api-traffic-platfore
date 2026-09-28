package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.RequestLog;
import java.time.Instant;
import java.util.UUID;

public record RequestLogResponse(long id, UUID requestId, Instant createdAt, String method, String path,
                                 String clientId, String clientIp, Long serviceId, String serviceName,
                                 Long instanceId, int statusCode, int latencyMs, int retryCount,
                                 String failureReason) {

    public static RequestLogResponse from(RequestLog l, String serviceName) {
        return new RequestLogResponse(l.getId(), l.getRequestId(), l.getCreatedAt(), l.getHttpMethod(), l.getPath(),
                l.getClientId(), l.getClientIp(), l.getServiceId(), serviceName, l.getInstanceId(), l.getStatusCode(),
                l.getLatencyMs(), l.getRetryCount(), l.getFailureReason());
    }
}
