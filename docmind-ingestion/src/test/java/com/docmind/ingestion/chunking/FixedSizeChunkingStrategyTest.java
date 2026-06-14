package com.docmind.ingestion.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for FixedSizeChunkingStrategy.
 * These tests are purely computational — no Spring context, no mocks needed.
 * They run in milliseconds and form the core regression suite for the chunking algorithm.
 */
@DisplayName("FixedSizeChunkingStrategy Unit Tests")
class FixedSizeChunkingStrategyTest {

    @Test
    @DisplayName("Should produce correct number of chunks for known input")
    void shouldProduceCorrectChunkCount() {
        // 100-char text, chunkSize=50, overlap=10, stride=40
        // Expected: chunk at [0,50), [40,90), [80,100) → 3 chunks
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(50, 10);
        String text = "a".repeat(100);

        List<String> chunks = strategy.chunk(text);

        assertThat(chunks).hasSize(3);
    }

    @Test
    @DisplayName("Should return single chunk when text is shorter than chunkSize")
    void shouldReturnSingleChunkForShortText() {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(1000, 100);
        String text = "Short text that fits in one chunk.";

        List<String> chunks = strategy.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    @DisplayName("Should return empty list for null input")
    void shouldReturnEmptyListForNull() {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(512, 64);
        List<String> chunks = strategy.chunk(null);
        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for blank input")
    void shouldReturnEmptyListForBlank() {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(512, 64);
        List<String> chunks = strategy.chunk("   ");
        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("Should return immutable list")
    void shouldReturnImmutableList() {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(10, 2);
        List<String> chunks = strategy.chunk("Hello World this is a test");
        assertThatThrownBy(() -> chunks.add("extra"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for invalid chunkSize")
    void shouldThrowForInvalidChunkSize() {
        assertThatThrownBy(() -> new FixedSizeChunkingStrategy(0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunkSize must be positive");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when overlap >= chunkSize")
    void shouldThrowWhenOverlapExceedsChunkSize() {
        assertThatThrownBy(() -> new FixedSizeChunkingStrategy(100, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlap");
    }

    @ParameterizedTest
    @ValueSource(ints = {64, 128, 512, 1024, 2048})
    @DisplayName("Should handle various chunk sizes without errors")
    void shouldHandleVariousChunkSizes(int chunkSize) {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(chunkSize, chunkSize / 8);
        String text = "Lorem ipsum dolor sit amet. ".repeat(100);
        List<String> chunks = strategy.chunk(text);
        assertThat(chunks).isNotEmpty();
        chunks.forEach(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(chunkSize));
    }

    @Test
    @DisplayName("strategyName should include chunk size and overlap")
    void shouldReturnDescriptiveStrategyName() {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(512, 64);
        assertThat(strategy.strategyName()).contains("512").contains("64");
    }
}
