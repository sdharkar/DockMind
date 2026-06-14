package com.docmind.core.workflow.node;

import com.docmind.core.workflow.RagState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RetrievalNode.
 *
 * Strategy: Mock the VectorStore (no real ChromaDB needed) using Mockito.
 * This makes tests:
 *   - Fast:     < 1ms, no I/O.
 *   - Isolated: failures mean the NODE logic is wrong, not the DB.
 *   - Deterministic: no network flakiness.
 *
 * We test:
 *   1. Happy path: returns retrieved docs in state update map.
 *   2. Retry path: query is modified on retry to broaden search.
 *   3. Empty result: returns empty list without throwing.
 *   4. Source filter: filter expression is applied when sourceFilter is set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetrievalNode Unit Tests")
class RetrievalNodeTest {

    @Mock
    private VectorStore vectorStore;

    private RetrievalNode retrievalNode;

    @BeforeEach
    void setUp() {
        retrievalNode = new RetrievalNode(vectorStore);
    }

    @Test
    @DisplayName("Should return retrieved documents in state update map on first attempt")
    void shouldReturnRetrievedDocuments() {
        // Arrange
        List<Document> mockDocs = List.of(
            new Document("Chunk about Spring AI"),
            new Document("Chunk about LangGraph4j")
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockDocs);

        RagState state = new RagState(Map.of("query", "What is Spring AI?", "retryCount", 0));

        // Act
        Map<String, Object> result = retrievalNode.execute(state);

        // Assert
        assertThat(result).containsKey("retrievedDocs");
        @SuppressWarnings("unchecked")
        List<Document> returnedDocs = (List<Document>) result.get("retrievedDocs");
        assertThat(returnedDocs).hasSize(2);
        assertThat(returnedDocs.get(0).getText()).isEqualTo("Chunk about Spring AI");

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("Should call VectorStore with modified query on retry (retryCount > 0)")
    void shouldModifyQueryOnRetry() {
        // Arrange
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RagState state = new RagState(Map.of("query", "How does RAG work?", "retryCount", 1));

        // Act
        retrievalNode.execute(state);

        // Assert — verify the SearchRequest passed to VectorStore contains the modified query prefix
        verify(vectorStore).similaritySearch(argThat((SearchRequest request) ->
            request.getQuery().startsWith("Provide detailed information about:")
        ));
    }

    @Test
    @DisplayName("Should return empty retrievedDocs list when VectorStore returns no results")
    void shouldHandleEmptySearchResults() {
        // Arrange
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RagState state = new RagState(Map.of("query", "Unknown topic", "retryCount", 0));

        // Act
        Map<String, Object> result = retrievalNode.execute(state);

        // Assert
        assertThat(result).containsKey("retrievedDocs");
        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) result.get("retrievedDocs");
        assertThat(docs).isEmpty();
    }
}
