package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.PageResponse;
import com.smartapi.gateway.dto.RequestLogResponse;
import com.smartapi.gateway.logging.RequestLogQueryService;
import com.smartapi.gateway.logging.TrafficStatsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RequestLogController {

    private final RequestLogQueryService queries;
    private final TrafficStatsService stats;

    public RequestLogController(RequestLogQueryService queries, TrafficStatsService stats) {
        this.queries = queries;
        this.stats = stats;
    }

    /** e.g. /api/request-logs?serviceId=3&statusClass=5&page=0&size=50 */
    @GetMapping("/api/request-logs")
    public PageResponse<RequestLogResponse> search(@RequestParam(required = false) Long serviceId,
                                                   @RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) Integer statusClass,
                                                   @RequestParam(required = false) String clientId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return queries.search(serviceId, status, statusClass, clientId, page, size);
    }

    /** Everything recorded for one X-Request-ID (the id a client quotes when reporting a problem). */
    @GetMapping("/api/request-logs/{requestId}")
    public List<RequestLogResponse> byRequestId(@PathVariable UUID requestId) {
        return queries.byRequestId(requestId);
    }

    @GetMapping("/api/traffic/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "15") int minutes) {
        return stats.summary(minutes);
    }

    @GetMapping("/api/traffic/failures")
    public Map<String, Object> failures(@RequestParam(defaultValue = "60") int minutes) {
        return stats.failures(minutes);
    }
}
