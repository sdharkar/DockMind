package com.docmind.core.workflow.node;

import com.docmind.core.workflow.RagState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LangGraph4j Node #1: Retrieval
 *
 * Queries ChromaDB for document chunks semantically similar to the user's query.
 * On retry (retryCount > 0), the query is slightly modified to broaden the search
 * and avoid retrieving the same unhelpful chunks.
 *
 * Inputs from state:
 *   - {@code query}        — the user's question
 *   - {@code sourceFilter} — optional metadata filter (e.g., "product-manual")
 *   - {@code retryCount}   — used to adjust retrieval strategy on retries
 *
 * Outputs to state (partial update map):
 *   - {@code retrievedDocs} — list of top-K similar Document chunks
 *                             (merged via Channels.appender — APPENDED, not replaced)
 *
 * Why appender for retrievedDocs on retry?
 *   On the first attempt: retrieve top-5 best matches.
 *   On retry: retrieve top-5 with a slightly broader query.
 *   The GenerationNode receives the union of all retrieved docs, giving the LLM
 *   more context to produce a grounded answer. This implements a lightweight
 *   "progressive retrieval" strategy without a full re-ranking pipeline.
 *
 * Spring AI SearchRequest API (2.0):
 *   SearchRequest.query(text).withTopK(k).withSimilarityThreshold(t).withFilterExpression(f)
 */
@Component
public class RetrievalNode {
    private static final Logger log = LoggerFactory.getLogger(RetrievalNode.class);


    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.65;

    private final VectorStore vectorStore;

    public RetrievalNode(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Execute retrieval and return a partial state update map for LangGraph4j.
     *
     * LangGraph4j node actions must return {@code Map<String, Object>} representing
     * the subset of state fields this node updates. The framework merges these into
     * the full RagState using the defined Channel strategies.
     */
    public Map<String, Object> execute(RagState state) {
        String query         = state.query();
        int    retryCount    = state.retryCount();

        // On retries, slightly modify the query to encourage different retrieval results.
        // This is a simple heuristic; production systems would use re-ranking or HyDE
        // (Hypothetical Document Embeddings) for better retry quality.
        String effectiveQuery = retryCount > 0
            ? "Provide detailed information about: " + query
            : query;

        log.debug("Retrieving docs: query='{}', topK={}, retry={}", effectiveQuery, TOP_K, retryCount);

        SearchRequest.Builder requestBuilder = SearchRequest.builder()
            .query(effectiveQuery)
            .topK(TOP_K)
            .similarityThreshold(SIMILARITY_THRESHOLD);

        // Apply optional metadata filter (e.g., filter by sourceTag = "product-manual")
        state.sourceFilter().ifPresent(filter -> {
            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            requestBuilder.filterExpression(
                filterBuilder.eq("sourceTag", filter).build()
            );
        });

        List<Document> docs = vectorStore.similaritySearch(requestBuilder.build());
        log.info("Retrieved {} documents for query (retry #{})", docs.size(), retryCount);

        // Return partial state update — only the fields this node changes.
        // LangGraph4j merges this map into RagState using Channels.appender for "retrievedDocs".
        return Map.of("retrievedDocs", docs);
    }
}
