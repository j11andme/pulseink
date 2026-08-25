package com.pulseink.exception.memory;

import com.pulseink.controller.memory.InsightController;
import com.pulseink.service.memory.InsightException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InsightController.class)
public class InsightExceptionHandler {

    @ExceptionHandler(InsightException.class)
    ResponseEntity<ApiError> insight(InsightException exception) {
        var status = switch (exception.code()) {
            case INSIGHT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSIGHT_SOURCE_NOT_READY, INSIGHT_DECISION_CONFLICT -> HttpStatus.CONFLICT;
            case INSIGHT_MODEL_FAILURE, INSIGHT_MODEL_OUTPUT_INVALID -> HttpStatus.BAD_GATEWAY;
            case INSIGHT_SEARCH_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR", "insight request is invalid"));
    }

    public record ApiError(String code, String message) {}
}
