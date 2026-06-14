package com.docmind.ingestion.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Paragraph-based chunking strategy.
 *
 * Splits text into paragraphs (delimited by double newlines) and groups consecutive paragraphs
 * together as long as their total character count is within the configured limit.
 * If a single paragraph exceeds the maximum size, it is split using fixed-size logic.
 *
 * This preserves natural semantic boundaries since paragraphs represent single cohesive thoughts.
 */
public class ParagraphChunkingStrategy implements ChunkingStrategy {
    private static final Logger log = LoggerFactory.getLogger(ParagraphChunkingStrategy.class);


    private final int maxChunkSize;
    private final int overlap;

    public ParagraphChunkingStrategy(int maxChunkSize, int overlap) {
        this.maxChunkSize = maxChunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Split text by paragraph delimiters (one or more empty lines)
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }

            // Case 1: The paragraph is too large to fit in a single chunk by itself.
            if (p.length() > maxChunkSize) {
                // Flush the current builder if it has content
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                // Split the massive paragraph into smaller fixed-size pieces with overlap
                chunks.addAll(splitMassiveParagraph(p));
                continue;
            }

            // Case 2: Adding this paragraph exceeds the max chunk size.
            if (currentChunk.length() + p.length() + 2 > maxChunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
            }

            // Append paragraph to the current chunk
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(p);
        }

        // Add any remaining text
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /** Fallback logic to split single large paragraphs that exceed maxChunkSize. */
    private List<String> splitMassiveParagraph(String p) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        int len = p.length();

        while (start < len) {
            int end = Math.min(start + maxChunkSize, len);
            pieces.add(p.substring(start, end));
            
            // Advance by step size (chunkSize - overlap) to maintain overlap
            int step = maxChunkSize - overlap;
            if (step <= 0) {
                step = maxChunkSize; // Avoid infinite loops if overlap >= maxChunkSize
            }
            
            if (end == len) {
                break;
            }
            start += step;
        }
        return pieces;
    }

    @Override
    public String strategyName() {
        return "paragraph-grouping";
    }
}
