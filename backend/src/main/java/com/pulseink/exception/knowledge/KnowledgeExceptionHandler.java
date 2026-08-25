package com.pulseink.exception.knowledge;

import com.pulseink.controller.knowledge.KnowledgeController;
import com.pulseink.service.knowledge.IngestKnowledgeUseCase.KnowledgeDocumentDuplicateException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = KnowledgeController.class)
public class KnowledgeExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidInput(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("was not found")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("KNOWLEDGE_DOCUMENT_NOT_FOUND", message));
        }
        String code = inputCode(message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(code, message));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflictOrUnavailable(IllegalStateException exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("not retryable")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("KNOWLEDGE_DOCUMENT_NOT_RETRYABLE", message));
        }
        if (message != null && message.contains("index")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiError("KNOWLEDGE_INDEX_UNAVAILABLE", message));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("KNOWLEDGE_CONFLICT", message));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("KNOWLEDGE_DOCUMENT_DUPLICATE",
                        "knowledge document already exists"));
    }

    @ExceptionHandler(KnowledgeDocumentDuplicateException.class)
    ResponseEntity<ApiError> duplicate(KnowledgeDocumentDuplicateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("KNOWLEDGE_DOCUMENT_DUPLICATE", exception.getMessage()));
    }

    private static String inputCode(String message) {
        if (message == null) {
            return "KNOWLEDGE_INVALID_INPUT";
        }
        if (message.contains("exceeds maximum size")) {
            return "KNOWLEDGE_FILE_TOO_LARGE";
        }
        if (message.contains("file is empty")) {
            return "KNOWLEDGE_FILE_EMPTY";
        }
        if (message.contains("not supported") || message.contains("extension is missing")) {
            return "KNOWLEDGE_FILE_TYPE_UNSUPPORTED";
        }
        if (message.contains("does not match detected")) {
            return "KNOWLEDGE_FILE_TYPE_MISMATCH";
        }
        if (message.contains("no extractable text") || message.contains("no chunkable text")) {
            return "KNOWLEDGE_TEXT_EMPTY";
        }
        if (message.contains("chunk count exceeds")) {
            return "KNOWLEDGE_CHUNK_LIMIT_EXCEEDED";
        }
        return "KNOWLEDGE_INVALID_INPUT";
    }

    public record ApiError(String code, String message) {
    }
}
