package com.pulseink.exception.publication;

import com.pulseink.controller.publication.PublicationController;
import com.pulseink.service.publishing.PublicationErrorCode;
import com.pulseink.service.publishing.PublicationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PublicationController.class)
public class PublicationExceptionHandler {

    @ExceptionHandler(PublicationException.class)
    ResponseEntity<ApiError> publication(PublicationException exception) {
        var status = switch (exception.code()) {
            case PUBLICATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONTENT_NOT_APPROVED, CONTENT_NOT_LATEST,
                    PUBLICATION_CONFLICT -> HttpStatus.CONFLICT;
            case CHANNEL_REJECTED -> HttpStatus.BAD_GATEWAY;
            case CONTENT_FORMAT_INVALID, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                PublicationErrorCode.VALIDATION_ERROR.name(),
                "publication request is invalid"));
    }

    public record ApiError(String code, String message) {}
}
