package com.docmind.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * RFC 7807-compliant Problem Details response for API errors.
 *
 * Why RFC 7807? It's the standard for HTTP error responses in REST APIs,
 * gives clients a predictable, machine-readable error format, and is
 * natively supported by Spring Boot 3.x's ProblemDetail.
 *
 * Example JSON:
 * {
 *   "type": "https://docmind.io/errors/document-not-found",
 *   "title": "Document Not Found",
 *   "status": 404,
 *   "detail": "No document found with ID: abc-123",
 *   "timestamp": "2025-01-15T10:30:00Z"
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        Instant timestamp,
        Map<String, Object> extensions
) {
    public ApiError(String type, String title, int status, String detail) {
        this(type, title, status, detail, Instant.now(), null);
    }
}
