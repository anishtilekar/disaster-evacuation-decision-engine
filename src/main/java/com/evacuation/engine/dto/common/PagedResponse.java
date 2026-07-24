package com.evacuation.engine.dto.common;

import java.util.List;

/**
 * Reusable pagination envelope for list-based REST responses across all modules
 * (disaster, graph, shelter, evacuation, decision engine, notification).
 * <p>
 * Contains no dependency on Spring Data's {@code Page<T>} — conversion from
 * Spring Data pages happens at the mapper/service layer, keeping this DTO
 * a pure, framework-agnostic transport object.
 */
public record PagedResponse<T>(

        List<T> content,

        int pageNumber,

        int pageSize,

        long totalElements,

        int totalPages,

        boolean first,

        boolean last

) {

    /**
     * Convenience factory for building a PagedResponse from raw pagination values.
     * Use this from a mapper when converting a Spring Data Page<T> (or any
     * other paginated source) into the API response shape.
     */
    public static <T> PagedResponse<T> of(
            List<T> content,
            int pageNumber,
            int pageSize,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {

        return new PagedResponse<>(
                content == null ? List.of() : content,
                pageNumber,
                pageSize,
                totalElements,
                totalPages,
                first,
                last
        );
    }

    /**
     * Convenience factory for an empty page (e.g., no results found).
     */
    public static <T> PagedResponse<T> empty(int pageNumber, int pageSize) {
        return new PagedResponse<>(
                List.of(),
                pageNumber,
                pageSize,
                0L,
                0,
                true,
                true
        );
    }
}