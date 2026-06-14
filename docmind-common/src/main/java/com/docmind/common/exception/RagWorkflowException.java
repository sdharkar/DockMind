package com.docmind.common.exception;

/**
 * Thrown when the RAG workflow cannot produce a valid answer
 * after exhausting all configured retries.
 */
public class RagWorkflowException extends RuntimeException {
    public RagWorkflowException(String message) {
        super(message);
    }

    public RagWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
