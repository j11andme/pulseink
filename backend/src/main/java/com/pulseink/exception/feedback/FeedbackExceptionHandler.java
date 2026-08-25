package com.pulseink.exception.feedback;

import com.pulseink.controller.feedback.FeedbackController;
import com.pulseink.service.feedback.InvalidFeedbackException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = FeedbackController.class)
public class FeedbackExceptionHandler {

    @ExceptionHandler(InvalidFeedbackException.class)
    ResponseEntity<ApiError> invalid(InvalidFeedbackException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> argument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", "metrics request is invalid"));
    }

    public record ApiError(String code, String message) {}
}
