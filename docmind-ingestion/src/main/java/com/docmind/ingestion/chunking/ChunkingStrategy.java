package com.docmind.ingestion.chunking;

import java.util.List;

/**
 * Strategy interface for splitting raw document text into chunks suitable for embedding.
 *
 * Why a strategy pattern here?
 * Different chunking strategies have very different trade-offs:
 * - FixedSizeChunkingStrategy: simple, predictable, easy to tune. Good baseline.
 * - SentenceBoundaryChunkingStrategy: higher quality but slower (NLP-heavy).
 * - SemanticChunkingStrategy: best quality, uses embedding similarity, but expensive.
 *
 * By programming to this interface, callers (DocumentIngestionService) are decoupled
 * from the chunking algorithm — you can swap strategies via Spring @Qualifier or config.
 *
 * This is the Open/Closed Principle in action: add new strategies without modifying
 * the ingestion service.
 */
public interface ChunkingStrategy {

    /**
     * Split the given text into a list of string chunks ready for embedding.
     *
     * @param text The full document text to chunk.
     * @return Ordered list of non-empty text chunks.
     */
    List<String> chunk(String text);

    /**
     * A human-readable name for this strategy, useful for logging and metrics.
     */
    String strategyName();
}
