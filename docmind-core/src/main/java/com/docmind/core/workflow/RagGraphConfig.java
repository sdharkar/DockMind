package com.docmind.core.workflow;

import com.docmind.core.workflow.node.GenerationNode;
import com.docmind.core.workflow.node.RetrievalNode;
import com.docmind.core.workflow.node.ValidationNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.CompileConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Spring @Configuration that builds and compiles the DocMind RAG pipeline as a
 * LangGraph4j {@link StateGraph}.
 *
 * Graph topology:
 * <pre>
 *   START
 *     │
 *     ▼
 *   [retrieve]          → RetrievalNode (ChromaDB similarity search)
 *     │
 *     ▼
 *   [generate]          → GenerationNode (OpenAI GPT-4o)
 *     │
 *     ▼
 *   [validate]          → ValidationNode (Ollama Mistral, LLM-as-Judge)
 *     │
 *     ├── isValid=true          ──────────────────────────────────▶ END
 *     ├── isValid=false, retry < max ──▶ [retrieve] (retry loop)
 *     └── isValid=false, retry >= max ─▶ END (with disclaimer)
 * </pre>
 *
 * Why compile the graph as a Spring @Bean?
 *   - {@link CompiledGraph} is THREAD-SAFE and IMMUTABLE after compilation.
 *   - Creating it as a singleton bean means the expensive graph-validation step
 *     (cycle detection, schema validation, node resolution) happens ONCE at startup,
 *     not on every request.
 *   - Spring's DI injects all node beans cleanly — no factory/registry boilerplate.
 *
 * Why InMemoryCheckpointSaver?
 *   - Allows workflow resumption within the same JVM session (e.g., if a node fails
 *     mid-pipeline, the graph can resume from the last saved checkpoint).
 *   - For production with multi-instance deployments, replace with a distributed
 *     checkpoint saver backed by Redis or PostgreSQL.
 *     TODO: Implement RedisCheckpointSaver for production.
 *
 * Node names (string constants) are the routing keys used in conditional edges.
 */
@Configuration
public class RagGraphConfig {
    private static final Logger log = LoggerFactory.getLogger(RagGraphConfig.class);


    // Node name constants — used as routing keys in conditional edges.
    public static final String NODE_RETRIEVE = "retrieve";
    public static final String NODE_GENERATE = "generate";
    public static final String NODE_VALIDATE = "validate";

    // Routing edge values returned by the conditional edge function
    public static final String ROUTE_RETRY = "retry";
    public static final String ROUTE_END   = "end";

    @Value("${docmind.rag.validation.max-retries:3}")
    private int maxRetries;

    /**
     * Builds and compiles the RAG StateGraph.
     *
     * Each {@code addNode(name, node_async(fn))} wraps a synchronous node function
     * as an async action — LangGraph4j executes nodes asynchronously for better throughput.
     *
     * The {@code node_async} and {@code edge_async} static imports are the idiomatic
     * LangGraph4j way to define node and edge actions in Java.
     *
     * @throws Exception if the graph definition is invalid (unreachable nodes, etc.)
     */
    @Bean
    public CompiledGraph<RagState> ragGraph(
            RetrievalNode  retrievalNode,
            GenerationNode generationNode,
            ValidationNode validationNode) throws Exception {

        log.info("Compiling RAG StateGraph (maxRetries={})", maxRetries);

        StateGraph<RagState> graph = new StateGraph<>(RagState.SCHEMA, RagState::new)

            // ── Node definitions ─────────────────────────────────────────────
            .addNode(NODE_RETRIEVE, node_async(retrievalNode::execute))
            .addNode(NODE_GENERATE, node_async(generationNode::execute))
            .addNode(NODE_VALIDATE, node_async(validationNode::execute))

            // ── Static edges ─────────────────────────────────────────────────
            .addEdge(START,         NODE_RETRIEVE)  // Entry: start → retrieve
            .addEdge(NODE_RETRIEVE, NODE_GENERATE)  // After retrieval → generate
            .addEdge(NODE_GENERATE, NODE_VALIDATE)  // After generation → validate

            // ── Conditional edge from validation ────────────────────────────
            //
            // Why use addConditionalEdges instead of a simple edge?
            //   The routing decision (retry vs. end) depends on runtime state (isValid,
            //   retryCount). Static edges cannot express this — only conditional edges can.
            //
            // The edge function returns a string key; the Map maps keys to target nodes.
            // Using named string constants (ROUTE_RETRY, ROUTE_END) prevents typos.
            .addConditionalEdges(
                NODE_VALIDATE,
                edge_async(this::routeAfterValidation),
                Map.of(
                    ROUTE_RETRY, NODE_RETRIEVE,  // invalid → back to retrieval
                    ROUTE_END,   END              // valid or maxRetries → end
                )
            );

        // Compile with in-memory checkpoint saver for state persistence across retries.
        // CompileConfig is LangGraph4j's builder for compilation options.
        CompiledGraph<RagState> compiled = graph.compile(
            CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .build()
        );

        log.info("RAG StateGraph compiled successfully.");
        return compiled;
    }

    /**
     * Routing function for the conditional edge after ValidationNode.
     *
     * Returns:
     *   - {@code ROUTE_END}   if the answer is valid OR max retries exhausted.
     *   - {@code ROUTE_RETRY} if the answer is invalid AND retries remain.
     *
     * This function is pure — it reads state but does NOT modify it.
     * State modification only happens inside nodes, not edge functions.
     */
    private String routeAfterValidation(RagState state) {
        boolean isValid    = state.isValid();
        int     retryCount = state.retryCount();

        if (isValid) {
            log.debug("Routing to END: answer validated successfully after {} retries.", retryCount);
            return ROUTE_END;
        }
        if (retryCount >= maxRetries) {
            log.warn("Routing to END: max retries ({}) exhausted. Returning best-effort answer.", maxRetries);
            return ROUTE_END;
        }
        log.debug("Routing to RETRY: retry #{} of {}", retryCount, maxRetries);
        return ROUTE_RETRY;
    }
}
