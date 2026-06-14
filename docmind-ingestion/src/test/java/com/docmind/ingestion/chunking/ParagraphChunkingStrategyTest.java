package com.docmind.ingestion.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ParagraphChunkingStrategy Unit Tests")
class ParagraphChunkingStrategyTest {

    @Test
    @DisplayName("Should return empty list for null or empty input")
    void shouldReturnEmptyForNullOrEmpty() {
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(500, 50);
        assertThat(strategy.chunk(null)).isEmpty();
        assertThat(strategy.chunk("  ")).isEmpty();
    }

    @Test
    @DisplayName("Should return single chunk for short text with single paragraph")
    void shouldReturnSingleChunkForShortText() {
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(500, 50);
        String text = "This is a single paragraph that is short.";
        List<String> chunks = strategy.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    @DisplayName("Should group consecutive paragraphs within max size")
    void shouldGroupParagraphs() {
        // maxChunkSize=100
        // Para 1 = 30 chars, Para 2 = 31 chars, Para 3 = 71 chars
        // Para 1 + 2 = 63 chars (fits)
        // Para 1 + 2 + 3 = 136 chars (exceeds) -> Chunk 1: Para 1 & 2, Chunk 2: Para 3
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(100, 10);
        String text = "Paragraph number one is first.\n\nParagraph number two is second.\n\nParagraph number three is third and is much longer to exceed the limit.";
        List<String> chunks = strategy.chunk(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo("Paragraph number one is first.\n\nParagraph number two is second.");
        assertThat(chunks.get(1)).isEqualTo("Paragraph number three is third and is much longer to exceed the limit.");
    }

    @Test
    @DisplayName("Should split massive paragraph exceeding max size")
    void shouldSplitMassiveParagraph() {
        // maxChunkSize=50, overlap=10, step=40
        // Para = 100 chars
        // Expected: split into 3 chunks
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(50, 10);
        String text = "a".repeat(100);
        List<String> chunks = strategy.chunk(text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(50);
    }

    @Test
    @DisplayName("Should return correct strategy name")
    void shouldReturnStrategyName() {
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(500, 50);
        assertThat(strategy.strategyName()).isEqualTo("paragraph-grouping");
    }
}
