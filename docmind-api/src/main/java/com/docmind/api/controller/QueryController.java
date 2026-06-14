package com.docmind.api.controller;

import com.docmind.common.dto.QueryRequest;
import com.docmind.common.dto.QueryResponse;
import com.docmind.core.workflow.RagWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the RAG Q&A endpoint.
 *
 * Endpoints:
 *   POST /api/v1/query  — Synchronous RAG query (request → validated answer).
 *
 * Design choices:
 *   - POST (not GET) for query: although queries are read-only, they carry a request body
 *     which is more natural with POST and avoids URL length limits for complex filters.
 *   - @Valid on @RequestBody: triggers Bean Validation (JSR-380) on QueryRequest fields.
 *     Invalid requests return 400 Bad Request with field-level error details via GlobalExceptionHandler.
 *   - The controller is intentionally thin — NO business logic here.
 *     Controllers = HTTP adapters; services = business logic. (Single Responsibility Principle)
 */
@RestController
@RequestMapping("/api/v1/query")
@Tag(name = "Query", description = "RAG Q&A endpoints — ask questions against the knowledge base")
public class QueryController {
    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final RagWorkflowService ragWorkflowService;

    public QueryController(RagWorkflowService ragWorkflowService) {
        this.ragWorkflowService = ragWorkflowService;
    }

    /**
     * Submit a natural-language question to the RAG pipeline.
     *
     * The pipeline: retrieve relevant docs → generate answer with GPT-4o →
     *               validate with Ollama Mistral → return grounded response.
     *
     * @param request Query with the question text and optional filters.
     * @return Grounded answer with source citations and observability metadata.
     */
    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary  = "Ask a question against the knowledge base",
        description = """
            Submits a question to the multi-step RAG pipeline:
            1. Retrieves relevant document chunks from ChromaDB.
            2. Generates an answer using OpenAI GPT-4o.
            3. Validates the answer with Ollama Mistral (LLM-as-Judge).
            4. Returns the grounded answer with source citations.
            
            The `validated` field indicates whether the answer passed LLM validation.
            If `false`, a disclaimer is included in the response.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Answer generated successfully",
            content = @Content(schema = @Schema(implementation = QueryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request (e.g., blank query)"),
        @ApiResponse(responseCode = "503", description = "AI service temporarily unavailable (circuit breaker open)")
    })
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        log.info("Received query request: query='{}', sourceFilter='{}'",
                  request.query(), request.sourceFilter());

        QueryResponse response = ragWorkflowService.query(request);
        return ResponseEntity.ok(response);
    }
}
