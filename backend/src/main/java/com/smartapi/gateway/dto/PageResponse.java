package com.smartapi.gateway.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable JSON for paged results (Spring Data's PageImpl JSON is not a stable contract). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
