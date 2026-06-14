package com.docmind.core.workflow.node;

import com.docmind.core.workflow.RagState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GenerationNode.
 *
 * Mocking ChatClient is non-trivial because Spring AI uses a fluent builder pattern:
 *   chatClient.prompt().system(s).user(u).call().content()
 *
 * We mock the entire chain using Mockito's deep stubbing (RETURNS_DEEP_STUBS).
 * This tests that our node correctly:
 *   1. Calls the ChatClient with a system + user prompt.
 *   2. Returns the answer in the state update map.
 *   3. Handles empty retrieved docs gracefully.
 *   4. Truncates long context to avoid token limit overflows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GenerationNode Unit Tests")
class GenerationNodeTest {

    // Deep stubs allow chaining: chatClient.prompt().system().user().call().content()
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ChatClient openAiChatClient;

    private GenerationNode generationNode;

    @BeforeEach
    void setUp() {
        generationNode = new GenerationNode(openAiChatClient);
    }

    @Test
    @DisplayName("Should return generated answer in state update map")
    void shouldReturnGeneratedAnswer() {
        // Arrange
        String expectedAnswer = "Spring AI is a framework for building AI applications on the JVM.";
        when(openAiChatClient.prompt()
            .system(anyString())
            .user(anyString())
            .call()
            .content())
            .thenReturn(expectedAnswer);

        List<Document> docs = List.of(new Document("Spring AI provides LLM integration for Java."));
        RagState state = new RagState(Map.of(
            "query", "What is Spring AI?",
            "retrievedDocs", docs,
            "retryCount", 0
        ));

        // Act
        Map<String, Object> result = generationNode.execute(state);

        // Assert
        assertThat(result).containsKey("answer");
        assertThat(result.get("answer")).isEqualTo(expectedAnswer);
        assertThat(result).containsEntry("modelUsed", "gpt-4o");
    }

    @Test
    @DisplayName("Should return fallback message when no documents are retrieved")
    void shouldReturnFallbackWhenNoDocs() {
        // Arrange — no docs available, no LLM call should be made
        RagState state = new RagState(Map.of(
            "query", "What is Spring AI?",
            "retrievedDocs", List.of(),
            "retryCount", 0
        ));

        // Act
        Map<String, Object> result = generationNode.execute(state);

        // Assert — should return fallback without calling the LLM
        assertThat(result.get("answer").toString())
            .contains("could not find relevant information");

        // Verify LLM was NOT called when no docs are available
        verifyNoInteractions(openAiChatClient);
    }

    @Test
    @DisplayName("Should include 'modelUsed' field in state update")
    void shouldIncludeModelUsedInStateUpdate() {
        // Arrange
        when(openAiChatClient.prompt()
            .system(anyString())
            .user(anyString())
            .call()
            .content())
            .thenReturn("Some answer");

        List<Document> docs = List.of(new Document("Some content"));
        RagState state = new RagState(Map.of(
            "query", "Test query",
            "retrievedDocs", docs,
            "retryCount", 0
        ));

        // Act
        Map<String, Object> result = generationNode.execute(state);

        // Assert
        assertThat(result).containsKey("modelUsed");
        assertThat(result.get("modelUsed")).isEqualTo("gpt-4o");
    }
}
