package com.pulseink.controller.publication;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.publication.Publication;
import com.pulseink.service.publishing.PublicationErrorCode;
import com.pulseink.service.publishing.PublicationException;
import com.pulseink.service.publishing.PublishContentUseCase;
import com.pulseink.service.publishing.QueryPublicationUseCase;
import com.pulseink.service.publishing.ReturnPublicationToEditingUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publication REST contract: POST /api/contents/{contentId}/publications (202 + Location),
 * GET /api/publications/{publicationId} and GET /api/runs/{runId}/publications.
 */
@RestController
@RequestMapping("/api")
public class PublicationController {

    private final PublishContentUseCase publish;
    private final QueryPublicationUseCase query;
    private final ReturnPublicationToEditingUseCase recovery;

    public PublicationController(PublishContentUseCase publish, QueryPublicationUseCase query,
                                 ReturnPublicationToEditingUseCase recovery) {
        this.publish = publish;
        this.query = query;
        this.recovery = recovery;
    }

    @PostMapping("/contents/{contentId}/publications")
    public ResponseEntity<PublicationResponse> publish(
            @PathVariable long contentId,
            @Valid @RequestBody PublishRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var created = publish.publish(new PublishContentUseCase.Command(
                contentId, request.contentVersionId(), channel(request.channel()),
                actorUserId(jwt)));
        return ResponseEntity.accepted()
                .location(URI.create("/api/publications/" + created.id()))
                .body(toResponse(created));
    }

    @GetMapping("/publications/{publicationId}")
    public PublicationResponse get(@PathVariable long publicationId) {
        return toResponse(query.get(publicationId));
    }

    @GetMapping("/runs/{runId}/publications")
    public List<PublicationResponse> listByRun(@PathVariable long runId) {
        return query.findByRunId(runId).stream()
                .map(PublicationController::toResponse).toList();
    }

    @PostMapping("/publications/{publicationId}/return-to-editing")
    public ResponseEntity<Void> returnToEditing(@PathVariable long publicationId) {
        recovery.returnToEditing(publicationId);
        return ResponseEntity.noContent().build();
    }

    private static CampaignChannel channel(String channel) {
        try {
            return CampaignChannel.valueOf(channel);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new PublicationException(PublicationErrorCode.VALIDATION_ERROR,
                    "unsupported channel: " + channel);
        }
    }

    private static long actorUserId(Jwt jwt) {
        if (jwt != null && jwt.getClaim("uid") instanceof Number number
                && number.longValue() > 0) {
            return number.longValue();
        }
        throw new IllegalStateException("jwt uid claim is missing or invalid");
    }

    private static PublicationResponse toResponse(Publication publication) {
        return new PublicationResponse(
                publication.id(),
                publication.runId(),
                publication.contentVersionId(),
                publication.channel(),
                publication.idempotencyKey(),
                publication.status(),
                publication.attemptCount(),
                publication.externalPostId(),
                publication.failureCode(),
                publication.failureMessage(),
                publication.createdAt(),
                publication.updatedAt(),
                publication.publishedAt());
    }

    public record PublishRequest(
            @Positive long contentVersionId,
            @NotBlank String channel) {
    }

    public record PublicationResponse(
            long id,
            long runId,
            long contentVersionId,
            CampaignChannel channel,
            UUID idempotencyKey,
            com.pulseink.domain.publication.PublicationStatus status,
            int attemptCount,
            UUID externalPostId,
            String failureCode,
            String failureMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt) {
    }
}
