package com.docmind.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the ingestion pipeline.
 *
 * All values are sourced from application.yml under the {@code docmind.ingestion} prefix.
 * Using @ConfigurationProperties (not @Value) because:
 *   - Type safety: Spring validates types at startup, not at first use.
 *   - Grouping: Related properties are in one cohesive class.
 *   - IDE support: Spring generates metadata so IDEs autocomplete properties.
 *
 * Example application.yml:
 * <pre>
 * docmind:
 *   ingestion:
 *     chunk-size: 1024
 *     overlap: 128
 *     max-file-size-mb: 50
 *     supported-mime-types:
 *       - application/pdf
 *       - text/plain
 * </pre>
 */
@ConfigurationProperties(prefix = "docmind.ingestion")
public record IngestionProperties(
        /** Character size of each text chunk. Default: 1024. */
        int chunkSize,
        /** Overlap between consecutive chunks. Default: 128. */
        int overlap,
        /** Maximum allowed file size in MB. Default: 50. */
        int maxFileSizeMb,
        /** Chunker type (e.g., "fixed-size", "paragraph"). Default: "fixed-size". */
        String chunkerType,
        /** MIME types accepted for ingestion. */
        java.util.List<String> supportedMimeTypes
) {
    // Compact constructor with defaults
    public IngestionProperties {
        if (chunkSize <= 0)   chunkSize = 1024;
        if (overlap < 0)      overlap   = 128;
        if (maxFileSizeMb <= 0) maxFileSizeMb = 50;
        if (chunkerType == null || chunkerType.isBlank()) chunkerType = "fixed-size";
        if (supportedMimeTypes == null || supportedMimeTypes.isEmpty()) {
            supportedMimeTypes = java.util.List.of("application/pdf", "text/plain");
        }
    }
}
