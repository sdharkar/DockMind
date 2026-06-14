package com.docmind.core.workflow;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared state object for the DocMind RAG pipeline.
 *
 * LangGraph4j uses a "blackboard" architecture — all nodes read from and write to
 * this single shared state object. Each node returns a PARTIAL map of fields to update;
 * LangGraph4j merges these updates using the defined {@link Channel} strategies.
 *
 * Why extend {@link AgentState} instead of using a plain record?
 *   - AgentState provides the merge/reduce machinery that makes conditional edges work.
 *   - Channels define HOW each field is merged when a node returns updates:
 *     * {@code lastValue()} — simple overwrite (e.g., the current answer).
 *     * {@code appender(...)} — accumulates into a list (e.g., all retrieved docs).
 *   - Thread-safety: AgentState's internal map is the single source of truth;
 *     node updates are applied atomically by the graph executor.
 *
 * State lifecycle:
 *   Initialization → [RetrievalNode] → [GenerationNode] → [ValidationNode]
 *                        ↑____________________(retry loop if !valid)_____|
 *
 * Key design decision: Using {@code Channels.appender} for {@code retrievedDocs}
 * means that on retry, newly retrieved docs are APPENDED to previous results.
 * This gives the generation node a richer context on subsequent attempts.
 * If we used {@code lastValue}, the retry would discard previous retrieval results.
 */
public class RagState extends AgentState {

    /**
     * The Channel schema defines the merge strategy for each state field.
     * This map is passed to {@link org.bsc.langgraph4j.StateGraph} at construction time.
     *
     * Fields:
     *   - query:         The user's original question (immutable after initialization).
     *   - sourceFilter:  Optional metadata filter for retrieval (e.g., "product-manual").
     *   - retrievedDocs: Documents retrieved from ChromaDB — appended on each retry.
     *   - answer:        The generated answer from GPT-4o — overwritten each generation cycle.
     *   - isValid:       Validation result from Ollama Mistral — overwritten each cycle.
     *   - retryCount:    Number of retrieve-generate cycles executed — incremented by ValidationNode.
     *   - validationReasoning: Explanation from the validator LLM (for debugging/logging).
     *   - modelUsed:     Name of the generation model (for response metadata).
     *   - startTimeMs:   Pipeline start time in epoch millis (for latency calculation).
     */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
        "query",               Channels.<String>base(() -> ""),
        "sourceFilter",        Channels.<String>base(() -> null),
        "retrievedDocs",       Channels.<Document>appender(ArrayList::new),
        "answer",              Channels.<String>base(() -> null),
        "isValid",             Channels.<Boolean>base(() -> false),
        "retryCount",          Channels.<Integer>base(() -> 0),
        "validationReasoning", Channels.<String>base(() -> null),
        "modelUsed",           Channels.<String>base(() -> null),
        "startTimeMs",         Channels.<Long>base(System::currentTimeMillis)
    );

    public RagState(Map<String, Object> initData) {
        super(initData);
    }

    // ── Typed accessors — prefer these over raw value() calls ──────────────

    public String query() {
        return this.<String>value("query").orElse("");
    }

    public Optional<String> sourceFilter() {
        return this.<String>value("sourceFilter");
    }

    /**
     * All retrieved documents from ChromaDB.
     * This is an accumulated list across retries (due to appender strategy).
     */
    @SuppressWarnings("unchecked")
    public List<Document> retrievedDocs() {
        return this.<List<Document>>value("retrievedDocs").orElse(List.of());
    }

    public Optional<String> answer() {
        return this.<String>value("answer");
    }

    public boolean isValid() {
        return this.<Boolean>value("isValid").orElse(false);
    }

    public int retryCount() {
        return this.<Integer>value("retryCount").orElse(0);
    }

    public Optional<String> validationReasoning() {
        return this.<String>value("validationReasoning");
    }

    public Optional<String> modelUsed() {
        return this.<String>value("modelUsed");
    }

    public long startTimeMs() {
        return this.<Long>value("startTimeMs").orElse(System.currentTimeMillis());
    }

    /** Convenience: compute elapsed time since pipeline started. */
    public long elapsedMs() {
        return System.currentTimeMillis() - startTimeMs();
    }
}
