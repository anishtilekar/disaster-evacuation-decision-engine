package com.evacuation.engine.dto.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Standard error response envelope returned by GlobalExceptionHandler
 * for all REST API errors across every module (disaster, graph, shelter,
 * evacuation, decision engine, notification).
 * <p>
 * Never includes stack traces or internal exception details —
 * only client-safe diagnostic information.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Builder.Default
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime timestamp = LocalDateTime.now();

    private final int status;

    private final String error;

    private final String message;

    private final String path;

    /**
     * Field-level validation errors, present only for VALIDATION_FAILED responses.
     * Key = field name, Value = validation message.
     */
    private final Map<String, String> fieldErrors;
}