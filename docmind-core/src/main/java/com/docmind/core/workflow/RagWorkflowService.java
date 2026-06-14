package com.docmind.core.workflow;

import com.docmind.common.dto.QueryRequest;
import com.docmind.common.dto.QueryResponse;
import com.docmind.common.exception.RagWorkflowException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Service that orchestrates the RAG pipeline for a given user query.
 *
 * This is the primary entry point from the REST API layer. It:
 *   1. Initializes the LangGraph4j state with the incoming query.
 *   2. Invokes the compiled {@link CompiledGraph} (the full RAG pipeline).
 *   3. Extracts the final state and maps it to a {@link QueryResponse} DTO.
 *   4. Records Micrometer metrics for observability.
 *
 * Resilience:
 *   The {@code @CircuitBreaker} annotation wraps the entire pipeline invocation.
 *   If the LLM services are consistently failing (e.g., OpenAI API down), the
 *   circuit breaker opens and immediately returns the fallback response without
 *   burning retries. Configuration is in application.yml under resilience4j.
 *
 * Thread safety:
 *   {@link CompiledGraph} is thread-safe — it can serve concurrent requests without
 *   synchronization. State is created fresh per invocation (passed as initialInput).
 *   This service is therefore safe to use as a singleton Spring bean.
 */
@Service
public class RagWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(RagWorkflowService.class);


    private final CompiledGraph<RagState> ragGraph;
    private final Timer                   queryTimer;
    private final MeterRegistry           meterRegistry;

    public RagWorkflowService(
            CompiledGraph<RagState> ragGraph,
            MeterRegistry meterRegistry) {
        this.ragGraph     = ragGraph;
        this.meterRegistry = meterRegistry;
        this.queryTimer   = meterRegistry.timer("docmind.rag.query.duration");
    }

    /**
     * Execute the full RAG pipeline for a user query.
     *
     * @param request The validated query request from the REST layer.
     * @return A fully populated response with answer, sources, and metadata.
     * @throws RagWorkflowException if the pipeline fails unexpectedly.
     */
    @CircuitBreaker(name = "ragPipeline", fallbackMethod = "fallbackResponse")
    public QueryResponse query(QueryRequest request) {
        log.info("Starting RAG pipeline for query: '{}'", request.query());

        return queryTimer.record(() -> {
            try {
                return executeGraph(request);
            } catch (Exception e) {
                throw new RagWorkflowException("RAG pipeline execution failed: " + e.getMessage(), e);
            }
        });
    }

    private QueryResponse executeGraph(QueryRequest request) throws Exception {
        long startMs = System.currentTimeMillis();

        // Initialize state for this invocation.
        // Each key must match a field in RagState.SCHEMA.
        Map<String, Object> initialState = new java.util.HashMap<>();
        initialState.put("query",      request.query());
        initialState.put("retryCount", 0);
        initialState.put("startTimeMs", startMs);
        if (request.sourceFilter() != null) {
            initialState.put("sourceFilter", request.sourceFilter());
        }

        // Invoke the compiled graph — runs Retrieve → Generate → Validate (→ retry loop)
        Optional<RagState> finalStateOpt = ragGraph.invoke(initialState);

        if (finalStateOpt.isEmpty()) {
            throw new RagWorkflowException("RAG graph returned no final state for query: " + request.query());
        }

        RagState finalState = finalStateOpt.get();
        return buildResponse(finalState, request, startMs);
    }

    /**
     * Maps the final {@link RagState} to the API {@link QueryResponse}.
     *
     * The response includes:
     * - The answer (with disclaimer if validation failed after max retries)
     * - Whether the answer was validated by the second LLM
     * - The top N source chunks used to ground the answer
     * - Observability metadata (model, latency, retries)
     */
    private QueryResponse buildResponse(RagState state, QueryRequest request, long startMs) {
        boolean isValid  = state.isValid();
        String  answer   = state.answer().orElse("No answer could be generated.");
        int     retries  = state.retryCount();
        long    latency  = System.currentTimeMillis() - startMs;

        // If we ran out of retries without a valid answer, add a disclaimer
        String disclaimer = (!isValid && retries > 0)
            ? "⚠️ This answer could not be fully verified against the knowledge base. Use with caution."
            : null;

        // Map top-N source chunks to SourceChunk DTOs for the response
        List<QueryResponse.SourceChunk> sources = state.retrievedDocs()
            .stream()
            .limit(request.maxSources())
            .map(doc -> new QueryResponse.SourceChunk(
                (String) doc.getMetadata().getOrDefault("documentId", "unknown"),
                (String) doc.getMetadata().getOrDefault("sourceTag", ""),
                doc.getText(),
                doc.getScore() != null ? doc.getScore() : 0.0
            ))
            .toList();

        // Record metrics
        meterRegistry.counter("docmind.rag.queries",
            "validated", String.valueOf(isValid),
            "retries",   String.valueOf(retries)
        ).increment();

        log.info("RAG pipeline complete: validated={}, retries={}, latency={}ms, sources={}",
                  isValid, retries, latency, sources.size());

        return QueryResponse.builder()
            .answer(answer)
            .validated(isValid)
            .disclaimer(disclaimer)
            .sources(sources)
            .modelUsed(state.modelUsed().orElse("gpt-4o"))
            .latencyMs(latency)
            .retryCount(retries)
            .generatedAt(Instant.now())
            .build();
    }

    /**
     * Circuit breaker fallback — invoked when the LLM services are unavailable.
     * Must have the same signature as {@link #query} plus a {@code Throwable} parameter.
     */
    public QueryResponse fallbackResponse(QueryRequest request, Throwable t) {
        log.error("RAG pipeline circuit breaker opened. Query='{}', cause={}",
                   request.query(), t.getMessage());

        meterRegistry.counter("docmind.rag.circuit-breaker.open").increment();

        return QueryResponse.builder()
            .answer("The AI service is temporarily unavailable. Please try again in a few moments.")
            .validated(false)
            .disclaimer("⚠️ Service unavailable — circuit breaker is open.")
            .sources(List.of())
            .modelUsed("none")
            .latencyMs(0)
            .retryCount(0)
            .generatedAt(Instant.now())
            .build();
    }
}
