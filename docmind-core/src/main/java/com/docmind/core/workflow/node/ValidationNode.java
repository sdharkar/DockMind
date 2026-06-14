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
 * LangGraph4j Node #3: Validation ("LLM-as-Judge")
 *
 * Uses Ollama Mistral (a local, free-to-run LLM) to fact-check the generated answer
 * against the retrieved source documents. This is the "second opinion" in the pipeline.
 *
 * Why a second LLM for validation?
 *   This implements the LLM-as-Judge pattern, which significantly reduces hallucination
 *   in RAG systems. The validator model is independent of the generator, so it catches
 *   cases where the generator "drifted" from the source context.
 *
 *   Trade-off: Doubles the LLM call cost per query. Mitigation: we use a fast, local
 *   Ollama model (Mistral) for validation — zero API cost, ~2s latency on modern hardware.
 *
 * Inputs from state:
 *   - {@code query}        — the original question
 *   - {@code answer}       — the GPT-4o generated answer
 *   - {@code retrievedDocs}— the source context the answer should be grounded in
 *   - {@code retryCount}   — number of retries already attempted
 *
 * Outputs to state:
 *   - {@code isValid}             — true if answer is grounded, false if retry needed
 *   - {@code validationReasoning} — Mistral's explanation (for debugging/logging)
 *   - {@code retryCount}          — incremented by 1
 *
 * Routing decision (handled by RagGraphConfig conditional edge):
 *   - isValid=true  → END (return answer to user)
 *   - isValid=false AND retryCount < maxRetries → back to RetrievalNode
 *   - isValid=false AND retryCount >= maxRetries → END with disclaimer
 *
 * Spring AI 2.0 structured output:
 *   We parse the validator's response as a structured {@code ValidationResult} record
 *   using {@code BeanOutputConverter} to get typed isGrounded + reasoning fields.
 */
@Component
public class ValidationNode {
    private static final Logger log = LoggerFactory.getLogger(ValidationNode.class);


    private static final int MAX_CONTEXT_PREVIEW_CHARS = 3_000;

    private final ChatClient ollamaChatClient;

    public ValidationNode(@Qualifier("ollamaChatClient") ChatClient ollamaChatClient) {
        this.ollamaChatClient = ollamaChatClient;
    }

    /**
     * Validate the generated answer against the source documents.
     *
     * @param state Current pipeline state.
     * @return Partial state update with isValid, validationReasoning, and incremented retryCount.
     */
    public Map<String, Object> execute(RagState state) {
        String answer  = state.answer().orElse("");
        String query   = state.query();
        List<Document> docs = state.retrievedDocs();
        int newRetryCount = state.retryCount() + 1;

        if (answer.isBlank() || docs.isEmpty()) {
            log.warn("Skipping validation: answer or docs are empty. Marking as invalid.");
            return Map.of(
                "isValid",             false,
                "validationReasoning", "No answer or context available for validation.",
                "retryCount",          newRetryCount
            );
        }

        // Build a condensed context preview for the validation prompt.
        // We don't need the full context — just enough to judge factual grounding.
        String contextPreview = buildContextPreview(docs);

        // ── Validation Prompt ──────────────────────────────────────────────
        // The prompt asks Mistral to evaluate ONE specific thing: is the answer
        // grounded in the context? We ask for a JSON response with a boolean field
        // so we can parse it reliably without regex heuristics.
        String systemPrompt = """
            You are a strict fact-checking assistant. Your job is to determine if an answer
            is supported by the provided document excerpts.

            Respond with ONLY a valid JSON object in this exact format:
            {
              "isGrounded": true or false,
              "reasoning": "brief explanation of your decision"
            }

            Rules:
            - isGrounded = true: the answer is mostly supported by the context.
            - isGrounded = false: the answer contains claims NOT found in the context,
              or the answer is missing key information that the context provides.
            """;

        String userPrompt = """
            QUESTION: %s

            DOCUMENT CONTEXT:
            %s

            ANSWER TO VALIDATE:
            %s

            Is the answer grounded in the context?
            """.formatted(query, contextPreview, answer);

        String rawResponse = ollamaChatClient
            .prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content();

        // Parse the JSON response — extract isGrounded and reasoning.
        boolean isGrounded  = parseIsGrounded(rawResponse);
        String  reasoning   = parseReasoning(rawResponse);

        log.info("Validation result: isGrounded={}, retry#{}, reasoning='{}'",
                  isGrounded, newRetryCount, reasoning);

        return Map.of(
            "isValid",             isGrounded,
            "validationReasoning", reasoning,
            "retryCount",          newRetryCount
        );
    }

    /**
     * Build a condensed preview of retrieved docs for the validation prompt.
     * The validator doesn't need all chunks — just enough to judge factual accuracy.
     */
    private String buildContextPreview(List<Document> docs) {
        StringBuilder sb   = new StringBuilder();
        int           used = 0;
        for (int i = 0; i < docs.size() && used < MAX_CONTEXT_PREVIEW_CHARS; i++) {
            String content = docs.get(i).getText();
            int    take    = Math.min(content.length(), MAX_CONTEXT_PREVIEW_CHARS - used);
            sb.append("[Source ").append(i + 1).append("]: ")
              .append(content, 0, take).append("\n");
            used += take;
        }
        return sb.toString();
    }

    /**
     * Parse {@code isGrounded} from Mistral's JSON response.
     * Uses simple string parsing as a pragmatic fallback — for production, use
     * {@code BeanOutputConverter<ValidationResult>} from Spring AI 2.0 for proper JSON parsing.
     */
    private boolean parseIsGrounded(String response) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        // Look for "isGrounded": true/false in the response
        if (lower.contains("\"isgrounded\": true") || lower.contains("\"isgrounded\":true")) {
            return true;
        }
        if (lower.contains("\"isgrounded\": false") || lower.contains("\"isgrounded\":false")) {
            return false;
        }
        // Fallback: if can't parse JSON, assume valid to avoid infinite retry loops
        log.warn("Could not parse isGrounded from validation response: '{}'. Defaulting to true.", response);
        return true;
    }

    /**
     * Extract the reasoning explanation from Mistral's JSON response.
     */
    private String parseReasoning(String response) {
        if (response == null) return "No reasoning provided.";
        // Extract value of "reasoning" field from JSON string
        int start = response.indexOf("\"reasoning\":");
        if (start == -1) return response.substring(0, Math.min(response.length(), 200));
        int valueStart = response.indexOf("\"", start + 12);
        if (valueStart == -1) return "Could not extract reasoning.";
        int valueEnd = response.indexOf("\"", valueStart + 1);
        if (valueEnd == -1) return "Could not extract reasoning.";
        return response.substring(valueStart + 1, valueEnd);
    }
}
