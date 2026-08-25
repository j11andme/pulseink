package com.pulseink.exception.run;

import com.pulseink.controller.run.RunController;
import com.pulseink.controller.run.RunEventController;
import com.pulseink.controller.run.RunEventController.InvalidLastEventIdException;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignNotFoundException;
import com.pulseink.service.campaign.QueryRunUseCase.RunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {RunController.class, RunEventController.class})
public class RunExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidRunInput(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_RUN", exception.getMessage()));
    }

    @ExceptionHandler(InvalidLastEventIdException.class)
    ResponseEntity<ApiError> invalidLastEventId(InvalidLastEventIdException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_LAST_EVENT_ID", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidRunBody(MethodArgumentNotValidException exception) {
        var message = exception.getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " must not be null")
                .orElse("run request body is invalid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_RUN", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableRunBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_RUN", "run request body is invalid"));
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    ResponseEntity<ApiError> campaignNotFound(CampaignNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("CAMPAIGN_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<ApiError> runNotFound(RunNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("RUN_NOT_FOUND", exception.getMessage()));
    }

    public record ApiError(String code, String message) {
    }
}
