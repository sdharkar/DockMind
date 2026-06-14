package com.docmind.core.workflow.node;

import com.docmind.core.workflow.RagState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LangGraph4j Node #2: Generation
 *
 * Uses OpenAI GPT-4o (via Spring AI ChatClient) to generate a grounded answer
 * from the retrieved document chunks. The prompt is carefully engineered to:
 *   1. Instruct the model to stay within the provided context.
 *   2. Acknowledge uncertainty rather than hallucinate.
 *   3. Reference specific source content in its answer.
 *
 * Inputs from state:
 *   - {@code query}        — the original user question
 *   - {@code retrievedDocs}— document chunks retrieved from ChromaDB
 *
 * Outputs to state:
 *   - {@code answer}    — the generated response (overwrites via lastValue channel)
 *   - {@code modelUsed} — model identifier for response metadata
 *
 * Spring AI 2.0 ChatClient API:
 *   - {@code chatClient.prompt().system(s).user(u).call().content()} for synchronous calls
 *   - {@code .stream().content()} returns a Flux<String> for streaming
 *
 * Why a separate @Qualifier("openAiChatClient")?
 *   DocMind uses TWO ChatClients: OpenAI (generation) + Ollama (validation).
 *   Spring AI 2.0 auto-configures a primary ChatClient.Builder from the first available model.
 *   We use @Qualifier to inject model-specific clients explicitly, avoiding ambiguity.
 */
@Component
public class GenerationNode {
    private static final Logger log = LoggerFactory.getLogger(GenerationNode.class);


    /**
     * Maximum number of characters from context to include in the prompt.
     * Prevents hitting GPT-4o's context window with too many retrieved chunks.
     * At ~4 chars/token, 8000 chars ≈ 2000 tokens of context.
     */
    private static final int MAX_CONTEXT_CHARS = 8_000;

    private final ChatClient openAiChatClient;

    public GenerationNode(@Qualifier("openAiChatClient") ChatClient openAiChatClient) {
        this.openAiChatClient = openAiChatClient;
    }

    /**
     * Generate an answer grounded in the retrieved context.
     *
     * @param state Current pipeline state from LangGraph4j.
     * @return Partial state update map with 'answer' and 'modelUsed' fields.
     */
    public Map<String, Object> execute(RagState state) {
        List<Document> docs  = state.retrievedDocs();
        String         query = state.query();

        if (docs.isEmpty()) {
            log.warn("No documents retrieved for query='{}'. Generating fallback response.", query);
            return Map.of(
                "answer",    "I could not find relevant information in the knowledge base to answer your question.",
                "modelUsed", "gpt-4o"
            );
        }

        // Build a context string from all retrieved chunks, truncated to stay within limits.
        // We number the sources so the LLM can reference them (e.g., "[Source 1]").
        String context = buildContext(docs);
        log.debug("Generating answer with {} docs, context length={} chars", docs.size(), context.length());

        // ── Prompt Engineering ────────────────────────────────────────────────
        // The system prompt sets strict grounding rules.
        // The user prompt provides the context + question in a clear structure.
        //
        // Key prompt design decisions:
        //   - "ONLY use the context below" → reduces hallucination
        //   - "If uncertain, say so" → graceful degradation over confident fabrication
        //   - Numbered sources → enables source citation in the answer
        String systemPrompt = """
            You are DocMind, a precise and helpful knowledge base assistant.
            Your ONLY job is to answer questions based on the provided document context.

            RULES:
            1. Answer ONLY from the provided context. Do not use external knowledge.
            2. If the context does not contain enough information to fully answer,
               say: "Based on the available documents, I can partially answer: ..."
               and explain what is missing.
            3. Cite sources by number (e.g., "[Source 2]") when referencing specific content.
            4. Be concise and accurate. Do not add filler text.
            5. If the question is completely unanswerable from context, say:
               "The knowledge base does not contain information about this topic."
            """;

        String userPrompt = """
            CONTEXT:
            %s

            QUESTION: %s

            ANSWER:
            """.formatted(context, query);

        String answer = openAiChatClient
            .prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content();

        log.info("Generated answer ({} chars) for query='{}'", answer.length(), query);

        return Map.of(
            "answer",    answer,
            "modelUsed", "gpt-4o"
        );
    }

    /**
     * Formats retrieved documents as a numbered list of source excerpts.
     * Truncates total context to {@code MAX_CONTEXT_CHARS} to respect token limits.
     */
    private String buildContext(List<Document> docs) {
        StringBuilder sb          = new StringBuilder();
        int           totalChars  = 0;

        for (int i = 0; i < docs.size(); i++) {
            String content = docs.get(i).getText();
            if (totalChars + content.length() > MAX_CONTEXT_CHARS) {
                // Truncate last chunk to fit within limit
                int remaining = MAX_CONTEXT_CHARS - totalChars;
                if (remaining > 100) { // Only include if there's meaningful space
                    sb.append("[Source ").append(i + 1).append("]: ")
                      .append(content, 0, remaining).append("...\n\n");
                }
                break;
            }
            sb.append("[Source ").append(i + 1).append("]: ").append(content).append("\n\n");
            totalChars += content.length();
        }

        return sb.toString().strip();
    }
}
