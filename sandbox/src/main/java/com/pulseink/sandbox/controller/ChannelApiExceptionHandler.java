package com.pulseink.sandbox.controller;

import com.pulseink.sandbox.domain.ChannelApiException;
import com.pulseink.sandbox.domain.ChannelApiException.Code;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ChannelPublishController.class)
public class ChannelApiExceptionHandler {

    @ExceptionHandler(ChannelApiException.class)
    ResponseEntity<ApiError> channel(ChannelApiException exception) {
        var status = switch (exception.code()) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case CHANNEL_POST_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                Code.VALIDATION_ERROR.name(), "channel publish request is invalid"));
    }

    public record ApiError(String code, String message) {}
}
