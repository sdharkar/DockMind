package com.docmind.common.exception;

/**
 * Thrown when document ingestion fails (e.g., unsupported file type, parsing error).
 */
public class DocumentIngestionException extends RuntimeException {
    public DocumentIngestionException(String message) {
        super(message);
    }

    public DocumentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
