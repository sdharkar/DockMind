package com.docmind.ingestion.config;

import com.docmind.ingestion.chunking.ChunkingStrategy;
import com.docmind.ingestion.chunking.FixedSizeChunkingStrategy;
import com.docmind.ingestion.chunking.ParagraphChunkingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the ingestion module.
 *
 * Provides the {@link ChunkingStrategy} bean configured from externalized
 * {@link IngestionProperties} values (sourced from application.yml).
 *
 * Why create this as a @Bean instead of @Component on FixedSizeChunkingStrategy?
 *   FixedSizeChunkingStrategy requires chunkSize and overlap as constructor args.
 *   These values come from @ConfigurationProperties at runtime, not at class-scan time.
 *   A @Bean factory method allows us to wire them together cleanly.
 *
 *   This also makes the strategy swappable: to use a different strategy (e.g., sentence-boundary),
 *   simply create an alternative @Bean here or add a @ConditionalOnProperty.
 */
@Configuration
public class IngestionAutoConfiguration {

    @Bean
    public ChunkingStrategy chunkingStrategy(IngestionProperties properties) {
        String type = properties.chunkerType().trim().toLowerCase();
        if ("paragraph".equals(type)) {
            return new ParagraphChunkingStrategy(
                properties.chunkSize(),
                properties.overlap()
            );
        }
        return new FixedSizeChunkingStrategy(
            properties.chunkSize(),
            properties.overlap()
        );
    }
}
