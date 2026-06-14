package com.docmind.api.controller;

import com.docmind.common.dto.DocumentMetadata;
import com.docmind.ingestion.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/**
 * REST controller for document ingestion operations.
 *
 * Endpoints:
 *   POST /api/v1/documents  — Ingest a document (PDF, plain text) into ChromaDB.
 *
 * File upload uses {@code multipart/form-data} — standard for binary uploads via REST.
 * The response includes a {@code Location} header with the document ID for REST resource semantics.
 *
 * Accepted file types (configured in application.yml under docmind.ingestion.supported-mime-types):
 *   - application/pdf
 *   - text/plain
 *   (Extensible: add DOCX, HTML, CSV by adding Tika parsers and updating the allowed list)
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Document ingestion — upload files to build the knowledge base")
public class IngestionController {
    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final DocumentIngestionService ingestionService;

    public IngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Upload and ingest a document into the vector knowledge base.
     *
     * @param file      The file to ingest (PDF or plain text). Max size configured in yml.
     * @param sourceTag Optional tag for metadata filtering (e.g., "product-manual").
     * @return 201 Created with DocumentMetadata in the body and a Location header.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary     = "Ingest a document into the knowledge base",
        description = """
            Uploads and processes a document through the ingestion pipeline:
            1. Validates file size and MIME type.
            2. Parses text using Apache Tika (supports PDF, DOCX, plain text).
            3. Splits into overlapping chunks for optimal RAG retrieval.
            4. Embeds each chunk via OpenAI text-embedding-3-small.
            5. Stores embeddings in ChromaDB with metadata for filtering.
            
            The returned `documentId` can be used to filter queries to this document.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Document ingested successfully",
            content = @Content(schema = @Schema(implementation = DocumentMetadata.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file (too large, empty, or unsupported type)"),
        @ApiResponse(responseCode = "500", description = "Internal ingestion failure")
    })
    public ResponseEntity<DocumentMetadata> ingestDocument(
            @Parameter(description = "File to ingest (PDF or plain text)", required = true)
            @RequestPart("file") MultipartFile file,

            @Parameter(description = "Optional source tag for metadata filtering (e.g., 'product-manual')")
            @RequestParam(value = "sourceTag", required = false) String sourceTag) {

        log.info("Ingestion request received: fileName={}, size={}bytes, sourceTag={}",
                  file.getOriginalFilename(), file.getSize(), sourceTag);

        DocumentMetadata metadata = ingestionService.ingest(file, sourceTag);

        // Return 201 Created with Location header pointing to the document resource
        URI location = URI.create("/api/v1/documents/" + metadata.documentId());
        return ResponseEntity
            .created(location)
            .body(metadata);
    }

    /**
     * List all ingested documents.
     *
     * @return 200 OK with a list of DocumentMetadata.
     */
    @GetMapping
    @Operation(
        summary     = "List all ingested documents",
        description = "Retrieves unique metadata of all documents currently stored in the vector store."
    )
    public ResponseEntity<List<DocumentMetadata>> listDocuments() {
        log.info("Received request to list all ingested documents");
        List<DocumentMetadata> documents = ingestionService.getIngestedDocuments();
        return ResponseEntity.ok(documents);
    }

    /**
     * Delete an ingested document from the vector store.
     *
     * @param documentId Unique ID of the document to delete.
     * @return 204 No Content on success.
     */
    @DeleteMapping("/{documentId}")
    @Operation(
        summary     = "Delete a document from the knowledge base",
        description = "Deletes all stored vector chunks and metadata associated with the given documentId."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Document deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Document not found or could not be deleted"),
        @ApiResponse(responseCode = "500", description = "Internal server failure during deletion")
    })
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "ID of the document to delete", required = true)
            @PathVariable("documentId") String documentId) {

        log.info("Received delete request for documentId='{}'", documentId);
        ingestionService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }
}
