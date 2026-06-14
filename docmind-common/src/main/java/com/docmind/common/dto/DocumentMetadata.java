package com.docmind.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Metadata record describing an ingested document.
 * Stored alongside vector embeddings in ChromaDB as metadata fields.
 *
 * ChromaDB metadata values must be strings, numbers, or booleans —
 * so Instant is serialized as epoch millis.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentMetadata(
        String documentId,
        String fileName,
        String sourceTag,       // e.g., "product-manual", "legal-docs"
        String mimeType,        // e.g., "application/pdf", "text/plain"
        long fileSizeBytes,
        int totalChunks,
        Instant ingestedAt
) {}
