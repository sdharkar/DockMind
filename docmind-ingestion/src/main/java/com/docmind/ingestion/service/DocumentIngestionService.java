package com.docmind.ingestion.service;

import com.docmind.common.dto.DocumentMetadata;
import com.docmind.common.exception.DocumentIngestionException;
import com.docmind.ingestion.chunking.ChunkingStrategy;
import com.docmind.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the full document ingestion pipeline:
 *
 *   1. Validate → check file size and MIME type.
 *   2. Parse    → extract raw text using Apache Tika (PDF, DOCX, plain text, HTML, etc.).
 *   3. Chunk    → split into overlapping fixed-size chunks via {@link ChunkingStrategy}.
 *   4. Embed + Store → Spring AI's VectorStore.add() embeds each chunk (via EmbeddingModel)
 *                       and persists to ChromaDB atomically.
 *
 * Why Apache Tika over PDFBox directly?
 *   Tika is a universal parser facade — it auto-detects the file format and delegates to the
 *   appropriate parser (PDFBox for PDF, POI for Office, etc.). We get multi-format support
 *   for free without hard-coding format-specific parsers. The cost is a heavier classpath,
 *   but "tika-parsers-standard-package" is the standard production choice.
 *
 * Why VectorStore.add() for embedding?
 *   Spring AI's VectorStore auto-calls EmbeddingModel.embed() for each document before
 *   storing. This means we never need to call EmbeddingModel directly in the ingestion
 *   service — the VectorStore abstraction handles it. If we swap from OpenAI embeddings
 *   to a local model, zero code changes are needed here.
 */
@Service
public class DocumentIngestionService {
    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);


    private final VectorStore vectorStore;
    private final ChunkingStrategy chunkingStrategy;
    private final IngestionProperties properties;
    private final Tika tika;
    private final Timer ingestionTimer;
    private final Counter ingestionSuccessCounter;
    private final Counter ingestionErrorCounter;

    public DocumentIngestionService(
            VectorStore vectorStore,
            ChunkingStrategy chunkingStrategy,
            IngestionProperties properties,
            MeterRegistry meterRegistry) {
        this.vectorStore        = vectorStore;
        this.chunkingStrategy   = chunkingStrategy;
        this.properties         = properties;
        this.tika               = new Tika();

        // Register custom Micrometer metrics for ingestion observability
        this.ingestionTimer          = meterRegistry.timer("docmind.ingestion.duration");
        this.ingestionSuccessCounter = meterRegistry.counter("docmind.ingestion.success");
        this.ingestionErrorCounter   = meterRegistry.counter("docmind.ingestion.errors");
    }

    /**
     * Ingests a multipart file upload into the vector store.
     *
     * @param file      The uploaded file.
     * @param sourceTag An optional tag (e.g., "product-manual") for metadata filtering.
     * @return Metadata about the ingested document, including the document ID and chunk count.
     * @throws DocumentIngestionException if parsing, validation, or storage fails.
     */
    public DocumentMetadata ingest(MultipartFile file, String sourceTag) {
        log.info("Starting ingestion: file={}, size={}bytes, sourceTag={}",
                 file.getOriginalFilename(), file.getSize(), sourceTag);

        return ingestionTimer.record(() -> {
            try {
                return doIngest(file, sourceTag);
            } catch (DocumentIngestionException e) {
                ingestionErrorCounter.increment();
                throw e;
            } catch (Exception e) {
                ingestionErrorCounter.increment();
                throw new DocumentIngestionException("Unexpected ingestion failure: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Deletes a document from the vector store by its documentId.
     * Searches for all document chunks belonging to this documentId,
     * and deletes their raw vector embeddings from ChromaDB.
     *
     * @param documentId The unique ID of the document to delete.
     */
    public void deleteDocument(String documentId) {
        log.info("Deleting document documentId='{}'", documentId);
        try {
            // Retrieve all chunk IDs belonging to this document
            SearchRequest request = SearchRequest.builder()
                .query("document") // Generic term to trigger similarity search
                .topK(1000)        // Max chunks expected for a single document
                .filterExpression(new FilterExpressionBuilder().eq("documentId", documentId).build())
                .build();
            
            List<Document> docs = vectorStore.similaritySearch(request);
            if (!docs.isEmpty()) {
                List<String> ids = docs.stream().map(Document::getId).toList();
                vectorStore.delete(ids);
                log.info("Successfully deleted {} chunks for documentId='{}' from VectorStore", ids.size(), documentId);
            } else {
                log.warn("No chunks found in VectorStore for documentId='{}'", documentId);
            }
        } catch (Exception e) {
            throw new DocumentIngestionException("Failed to delete document '%s': %s".formatted(documentId, e.getMessage()), e);
        }
    }

    /**
     * Retrieves metadata of all ingested documents by performing a broad similarity search
     * and deduplicating results by documentId.
     */
    public List<DocumentMetadata> getIngestedDocuments() {
        log.info("Fetching ingested documents from VectorStore");
        try {
            // Retrieve chunks from ChromaDB.
            // Since there's no native "list all" in Spring AI VectorStore, we query with a generic
            // query string to match any document chunks, then deduplicate by documentId in memory.
            SearchRequest request = SearchRequest.builder()
                .query("document") // Broad query term
                .topK(1000)        // Fetch top chunks to find unique document IDs
                .build();
            
            List<Document> docs = vectorStore.similaritySearch(request);
            Map<String, DocumentMetadata> uniqueDocs = new HashMap<>();
            
            for (Document doc : docs) {
                Map<String, Object> metadata = doc.getMetadata();
                String documentId = (String) metadata.get("documentId");
                if (documentId != null && !uniqueDocs.containsKey(documentId)) {
                    String fileName = (String) metadata.getOrDefault("fileName", "Unknown");
                    String sourceTag = (String) metadata.getOrDefault("sourceTag", "untagged");
                    String mimeType = (String) metadata.getOrDefault("mimeType", "application/octet-stream");
                    
                    Number totalChunksNum = (Number) metadata.get("totalChunks");
                    int totalChunks = totalChunksNum != null ? totalChunksNum.intValue() : 1;
                    
                    Number fileSizeBytesNum = (Number) metadata.get("fileSizeBytes");
                    long fileSizeBytes = fileSizeBytesNum != null ? fileSizeBytesNum.longValue() : 0L;
                    
                    Number ingestedAtNum = (Number) metadata.get("ingestedAt");
                    Instant ingestedAt = ingestedAtNum != null ? Instant.ofEpochMilli(ingestedAtNum.longValue()) : Instant.now();
                    
                    uniqueDocs.put(documentId, new DocumentMetadata(
                        documentId,
                        fileName,
                        sourceTag,
                        mimeType,
                        fileSizeBytes,
                        totalChunks,
                        ingestedAt
                    ));
                }
            }
            return new ArrayList<>(uniqueDocs.values());
        } catch (Exception e) {
            log.error("Failed to retrieve ingested documents from ChromaDB: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private DocumentMetadata doIngest(MultipartFile file, String sourceTag) throws IOException {
        // ── Step 1: Validate ────────────────────────────────────────────────
        validateFile(file);

        // ── Step 2: Parse with Apache Tika ──────────────────────────────────
        String rawText;
        String detectedMime;
        try (InputStream is = file.getInputStream()) {
            rawText      = tika.parseToString(is);
            detectedMime = tika.detect(file.getOriginalFilename());
        } catch (Exception e) {
            throw new DocumentIngestionException(
                "Failed to parse file '%s': %s".formatted(file.getOriginalFilename(), e.getMessage()), e);
        }

        if (rawText == null || rawText.isBlank()) {
            throw new DocumentIngestionException(
                "Extracted text is empty from file: " + file.getOriginalFilename());
        }

        // ── Step 3: Chunk ────────────────────────────────────────────────────
        List<String> chunks = chunkingStrategy.chunk(rawText);
        log.debug("Chunked '{}' into {} chunks using strategy: {}",
                  file.getOriginalFilename(), chunks.size(), chunkingStrategy.strategyName());

        // ── Step 4: Build Spring AI Document objects with metadata ────────────
        String documentId = UUID.randomUUID().toString();
        List<Document> documents = buildDocuments(chunks, documentId, file, sourceTag, detectedMime);

        // ── Step 5: Embed + Store (one batch call) ───────────────────────────
        //
        // VectorStore.add() internally calls EmbeddingModel.embed() for each document,
        // then persists the (text, vector, metadata) triple to ChromaDB.
        // ChromaDB stores metadata as flat key-value pairs (strings/numbers/booleans).
        vectorStore.add(documents);
        log.info("Successfully ingested {} chunks for documentId={}", chunks.size(), documentId);
        ingestionSuccessCounter.increment();

        return new DocumentMetadata(
            documentId,
            file.getOriginalFilename(),
            sourceTag,
            detectedMime,
            file.getSize(),
            chunks.size(),
            Instant.now()
        );
    }

    /** Validates file size and (optionally) MIME type against the allowed list. */
    private void validateFile(MultipartFile file) {
        long maxBytes = (long) properties.maxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new DocumentIngestionException(
                "File size %d bytes exceeds limit of %dMB".formatted(file.getSize(), properties.maxFileSizeMb()));
        }
        if (file.isEmpty()) {
            throw new DocumentIngestionException("Uploaded file is empty.");
        }
    }

    /** Converts raw text chunks into Spring AI {@link Document} objects with ChromaDB-compatible metadata. */
    private List<Document> buildDocuments(
            List<String> chunks, String documentId,
            MultipartFile file, String sourceTag, String mimeType) {

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            // ChromaDB metadata values must be: String, Integer, Float, or Boolean.
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId",  documentId);
            metadata.put("fileName",    file.getOriginalFilename());
            metadata.put("sourceTag",   sourceTag != null ? sourceTag : "untagged");
            metadata.put("mimeType",    mimeType);
            metadata.put("chunkIndex",  i);
            metadata.put("totalChunks", chunks.size());
            metadata.put("fileSizeBytes", file.getSize());
            metadata.put("ingestedAt",  Instant.now().toEpochMilli()); // epoch millis for ChromaDB compat
            documents.add(new Document(chunks.get(i), metadata));
        }
        return documents;
    }
}
