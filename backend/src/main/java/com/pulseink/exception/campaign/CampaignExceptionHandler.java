package com.pulseink.exception.campaign;

import com.pulseink.controller.campaign.CampaignController;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CampaignController.class)
public class CampaignExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidCampaignInput(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_CAMPAIGN", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidCampaignBody(MethodArgumentNotValidException exception) {
        var message = exception.getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " must not be blank")
                .orElse("campaign request body is invalid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_CAMPAIGN", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableCampaignBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_CAMPAIGN", "campaign request body is invalid"));
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    ResponseEntity<ApiError> campaignNotFound(CampaignNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("CAMPAIGN_NOT_FOUND", exception.getMessage()));
    }

    public record ApiError(String code, String message) {
    }
}
