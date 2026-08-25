package com.pulseink.sandbox.controller;

import com.pulseink.sandbox.service.ChannelPublishingService;
import com.pulseink.sandbox.service.ChannelPublishingService.PublishCommand;
import com.pulseink.sandbox.service.ChannelPublishingService.PublishResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channel Sandbox HTTP contract: POST /channel-api/v1/posts with an Idempotency-Key header and
 * GET /channel-api/v1/posts/by-idempotency-key/{key} for receipt lookup.
 */
@RestController
@RequestMapping("/channel-api/v1")
public class ChannelPublishController {

    private final ChannelPublishingService service;

    public ChannelPublishController(ChannelPublishingService service) {
        this.service = service;
    }

    @PostMapping("/posts")
    public ResponseEntity<PublishReceiptResponse> publish(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody PublishRequest request) {
        PublishResult result = service.publish(new PublishCommand(
                request.sourcePublicationId(), request.contentVersionId(), request.channel(),
                request.content(), request.sourceRefs()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result));
    }

    @GetMapping("/posts/by-idempotency-key/{key}")
    public PublishReceiptResponse get(@PathVariable UUID key) {
        return toResponse(service.findByKey(key));
    }

    private static PublishReceiptResponse toResponse(PublishResult result) {
        return new PublishReceiptResponse(result.externalPostId(), result.idempotencyKey(),
                result.channel(), result.publishedAt(), result.replayed());
    }

    public record PublishRequest(
            @Positive long sourcePublicationId,
            @Positive long contentVersionId,
            @NotBlank String channel,
            @NotNull Map<String, Object> content,
            @NotNull List<String> sourceRefs) {
    }

    public record PublishReceiptResponse(
            UUID externalPostId,
            UUID idempotencyKey,
            String channel,
            Instant publishedAt,
            boolean replayed) {
    }
}
