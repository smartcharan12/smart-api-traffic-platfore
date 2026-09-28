package com.smartapi.gateway.repository;

import com.smartapi.gateway.entity.RequestLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    /**
     * Newest first, each filter optional. The status range covers "all 5xx" (500..599) and single codes.
     * Backed by idx_request_logs_created_at / idx_request_logs_service_created / idx_request_logs_status_created.
     */
    @Query("""
            select l from RequestLog l
            where (:serviceId is null or l.serviceId = :serviceId)
              and (:statusFrom is null or l.statusCode >= :statusFrom)
              and (:statusTo is null or l.statusCode <= :statusTo)
              and (:clientId is null or l.clientId = :clientId)
            order by l.createdAt desc
            """)
    Page<RequestLog> search(Long serviceId, Integer statusFrom, Integer statusTo, String clientId, Pageable pageable);

    List<RequestLog> findByRequestId(UUID requestId);

    @Modifying
    @Query("delete from RequestLog l where l.createdAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
