package com.pulseink.exception.content;

import com.pulseink.controller.content.ContentController;
import com.pulseink.service.content.ContentErrorCode;
import com.pulseink.service.content.ContentWorkflowException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ContentController.class)
public class ContentExceptionHandler {

    @ExceptionHandler(ContentWorkflowException.class)
    ResponseEntity<ApiError> workflow(ContentWorkflowException exception) {
        var status = switch (exception.code()) {
            case CONTENT_NOT_FOUND, CONTENT_VERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONTENT_VERSION_CONFLICT, CONTENT_NOT_LATEST, RUN_NOT_EDITABLE,
                    RUN_NOT_WAITING_APPROVAL, CONTENT_ALREADY_APPROVED -> HttpStatus.CONFLICT;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                ContentErrorCode.VALIDATION_ERROR.name(), "content request is invalid"));
    }

    public record ApiError(String code, String message) {}
}
