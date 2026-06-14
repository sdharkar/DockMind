package com.docmind.api.exception;

import com.docmind.common.dto.ApiError;
import com.docmind.common.exception.DocumentIngestionException;
import com.docmind.common.exception.RagWorkflowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Global exception handler — maps domain exceptions to RFC 7807 Problem Details responses.
 *
 * Using @RestControllerAdvice (not per-controller try-catch) because:
 *   - Single Place: all error mapping in one class (Open/Closed Principle — add new
 *     exception types without touching any controller).
 *   - Consistent: all errors return the same ApiError shape — clients parse one format.
 *   - Logging: centralized error logging with appropriate severity levels.
 *
 * HTTP status codes chosen per REST best practices:
 *   - 400 Bad Request: client sent invalid input (validation failures, bad file type).
 *   - 413 Payload Too Large: file exceeds configured size limit.
 *   - 503 Service Unavailable: LLM or external service is down (circuit breaker open).
 *   - 500 Internal Server Error: unexpected server-side failures.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);


    private static final String BASE_TYPE = "https://docmind.io/errors/";

    /** Handle Bean Validation failures (@Valid on @RequestBody) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors()
            .stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));

        log.warn("Request validation failed: {}", detail);

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ApiError(
                BASE_TYPE + "validation-error",
                "Request Validation Failed",
                400,
                detail
            ));
    }

    /** Handle document ingestion failures (bad file, parse error, etc.) */
    @ExceptionHandler(DocumentIngestionException.class)
    public ResponseEntity<ApiError> handleIngestionException(DocumentIngestionException ex) {
        log.error("Document ingestion failed: {}", ex.getMessage());

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ApiError(
                BASE_TYPE + "ingestion-error",
                "Document Ingestion Failed",
                400,
                ex.getMessage()
            ));
    }

    /** Handle file size exceeded (Spring's multipart size limit) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleFileSizeException(MaxUploadSizeExceededException ex) {
        log.warn("File upload too large: {}", ex.getMessage());

        return ResponseEntity
            .status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new ApiError(
                BASE_TYPE + "file-too-large",
                "File Size Exceeded",
                413,
                "The uploaded file exceeds the maximum allowed size."
            ));
    }

    /** Handle RAG workflow failures (LLM errors, state issues, etc.) */
    @ExceptionHandler(RagWorkflowException.class)
    public ResponseEntity<ApiError> handleWorkflowException(RagWorkflowException ex) {
        log.error("RAG workflow error: {}", ex.getMessage(), ex);

        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ApiError(
                BASE_TYPE + "workflow-error",
                "AI Workflow Failed",
                503,
                "The AI pipeline encountered an error. Please try again."
            ));
    }

    /** Catch-all for unexpected exceptions — always return 500 with a generic message
     *  (don't leak internal details in production responses). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiError(
                BASE_TYPE + "internal-error",
                "Internal Server Error",
                500,
                "An unexpected error occurred. Please contact support if this persists."
            ));
    }
}
