package com.docmind.ingestion.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size chunking with configurable overlap.
 *
 * Algorithm:
 *   Given text of N characters, chunk size C, and overlap O:
 *   - Chunk 1: chars [0, C)
 *   - Chunk 2: chars [C-O, 2C-O)
 *   - ...continuing with stride = C - O
 *
 * Trade-offs vs alternatives:
 *   ✅ Simple and predictable — easy to reason about chunk boundaries.
 *   ✅ Fast — O(N) time, zero NLP dependencies.
 *   ✅ Configurable overlap — prevents context loss at chunk boundaries.
 *   ❌ Can split mid-sentence — may hurt semantic coherence.
 *   ❌ Not token-aware — real LLM token counts may vary from char counts.
 *
 * For production at scale, consider {@code SentenceBoundaryChunkingStrategy}
 * (NLP-based) or Spring AI's built-in {@code TokenTextSplitter} (token-aware).
 *
 * @see ChunkingStrategy
 */
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;

    /**
     * @param chunkSize Number of characters per chunk (recommended: 512–2048).
     * @param overlap   Character overlap between consecutive chunks (recommended: 10–15% of chunkSize).
     */
    public FixedSizeChunkingStrategy(int chunkSize, int overlap) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be >= 0 and < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap   = overlap;
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> chunks = new ArrayList<>();
        int stride = chunkSize - overlap;
        int start  = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end == text.length()) break;
            start += stride;
        }

        return List.copyOf(chunks); // return immutable list
    }

    @Override
    public String strategyName() {
        return "fixed-size(chunkSize=%d, overlap=%d)".formatted(chunkSize, overlap);
    }
}
