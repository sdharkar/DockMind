package com.docmind.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming request DTO for the RAG query endpoint.
 *
 * Design note: Using a Java record for immutability — records are ideal for DTOs
 * since they are pure data carriers with no business logic. Lombok @Builder is not
 * needed as records have a compact constructor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryRequest(

        @NotBlank(message = "Query must not be blank")
        @Size(min = 3, max = 2000, message = "Query must be between 3 and 2000 characters")
        String query,

        /**
         * Optional: restrict retrieval to a specific document source tag
         * (e.g., "product-manual", "legal-docs"). Null means search all documents.
         */
        String sourceFilter,

        /**
         * Optional: maximum number of source chunks to include in the response.
         * Defaults to 3 if not specified.
         */
        Integer maxSources
) {
    // Compact constructor with defaults
    public QueryRequest {
        if (maxSources == null) maxSources = 3;
    }
}
