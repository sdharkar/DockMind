package com.docmind.core.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI ChatClient configuration — wires TWO distinct ChatClient beans:
 *
 *   1. {@code openAiChatClient} — uses OpenAI GPT-4o for answer generation.
 *      GPT-4o offers the best quality/cost ratio for RAG generation.
 *
 *   2. {@code ollamaChatClient} — uses local Ollama Mistral for answer validation.
 *      Mistral is free, private (runs locally), and fast enough for validation (~2s).
 *
 * Why two separate ChatClient beans instead of one?
 *   Spring AI 2.0 allows multiple ChatModel beans of different types. By creating
 *   named @Qualifier-based ChatClient beans here, nodes can inject exactly the LLM
 *   they need without knowing how models are configured. This is the Single Responsibility
 *   Principle: GenerationNode only knows "I need an OpenAI client", not HOW it's built.
 *
 * Why is openAiChatClient @Primary?
 *   If any Spring component (e.g., an advisor) auto-injects ChatClient without a qualifier,
 *   it gets the OpenAI client. This is the sensible default for most AI operations.
 *
 * Connection settings (model names, temperature, API keys) are externalized in
 * application.yml under spring.ai.openai and spring.ai.ollama — not hardcoded here.
 */
@Configuration
public class AiClientConfig {

    /**
     * Primary ChatClient backed by OpenAI GPT-4o.
     * Used by GenerationNode for answer generation.
     *
     * System prompt: set per-node, not globally, to allow node-specific personalization.
     */
    @Bean
    @Primary
    @Qualifier("openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
            // No global system prompt — nodes set their own system prompts.
            // This keeps the client reusable across different contexts.
            .build();
    }

    /**
     * Secondary ChatClient backed by Ollama (local Mistral model).
     * Used by ValidationNode for LLM-as-Judge fact-checking.
     *
     * Ollama must be running locally: docker run -p 11434:11434 ollama/ollama
     * Then pull the model: docker exec -it <container> ollama pull mistral
     */
    @Bean
    @Qualifier("ollamaChatClient")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel)
            .build();
    }

    /**
     * Wires the primary EmbeddingModel bean.
     * Resolves the autowiring ambiguity when both OpenAI and Ollama starters are present.
     */
    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(@Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        return openAiEmbeddingModel;
    }
}
