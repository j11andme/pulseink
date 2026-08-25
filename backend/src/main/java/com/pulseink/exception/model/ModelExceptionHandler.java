package com.pulseink.exception.model;

import com.pulseink.controller.model.ModelChatController;
import com.pulseink.service.model.ChatWithModelUseCase.InvalidModelInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ModelChatController.class)
public class ModelExceptionHandler {

    @ExceptionHandler(InvalidModelInputException.class)
    ResponseEntity<ApiError> invalidModelInput(
            InvalidModelInputException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_MODEL_INPUT", exception.getMessage()));
    }

    record ApiError(String code, String message) {}
}
