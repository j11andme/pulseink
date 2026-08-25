package com.pulseink.controller.content;

import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.domain.content.ApprovalRecord;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentOrigin;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.content.ReviewIssue;
import com.pulseink.domain.content.ReviewReport;
import com.pulseink.service.content.ApproveContentUseCase;
import com.pulseink.service.content.CreateContentVersionUseCase;
import com.pulseink.service.content.QueryContentUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ContentController {

    private final QueryContentUseCase query;
    private final CreateContentVersionUseCase versions;
    private final ApproveContentUseCase approvals;

    public ContentController(QueryContentUseCase query,
                             CreateContentVersionUseCase versions,
                             ApproveContentUseCase approvals) {
        this.query = Objects.requireNonNull(query);
        this.versions = Objects.requireNonNull(versions);
        this.approvals = Objects.requireNonNull(approvals);
    }

    @GetMapping("/runs/{runId}/contents")
    public RunContentsResponse list(@PathVariable long runId) {
        return new RunContentsResponse(
                query.findByRunId(runId).stream().map(ContentController::toResponse).toList(),
                query.findReviewsByRunId(runId).stream()
                        .map(ContentController::toResponse).toList());
    }

    @GetMapping("/contents/{contentId}")
    public ContentResponse detail(@PathVariable long contentId) {
        return toResponse(query.get(contentId));
    }

    @PostMapping("/contents/{contentId}/versions")
    public ResponseEntity<ContentVersionResponse> createVersion(
            @PathVariable long contentId,
            @Valid @RequestBody CreateVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var created = versions.createVersion(new CreateContentVersionUseCase.Command(
                contentId, request.expectedCurrentVersionNo(), request.expectedItemVersion(),
                request.content(), request.sourceRefs(), actorUserId(jwt)));
        return ResponseEntity.created(URI.create(
                        "/api/contents/" + contentId + "/versions/" + created.id()))
                .body(toResponse(created));
    }

    @PostMapping("/contents/{contentId}/approve")
    public ResponseEntity<ApprovalResponse> approve(
            @PathVariable long contentId,
            @Valid @RequestBody ApproveRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var approved = approvals.approve(new ApproveContentUseCase.Command(
                contentId, request.contentVersionId(), request.expectedCurrentVersionNo(),
                request.expectedItemVersion(), request.comment(), actorUserId(jwt)));
        return ResponseEntity.created(URI.create(
                        "/api/contents/" + contentId + "/approvals/" + approved.id()))
                .body(toResponse(approved));
    }

    private static long actorUserId(Jwt jwt) {
        if (jwt != null && jwt.getClaim("uid") instanceof Number number
                && number.longValue() > 0) {
            return number.longValue();
        }
        throw new IllegalStateException("jwt uid claim is missing or invalid");
    }

    private static ContentResponse toResponse(ContentItem item) {
        return new ContentResponse(item.id(), item.runId(), item.taskId(),
                item.currentVersionNo(), item.version(), item.createdAt(), item.updatedAt(),
                item.versions().stream().map(ContentController::toResponse).toList(),
                item.approvals().stream().map(ContentController::toResponse).toList());
    }

    private static ContentVersionResponse toResponse(ContentVersion version) {
        return new ContentVersionResponse(version.id(), version.versionNo(), version.content(),
                version.sourceRefs(), version.origin(), version.sourceArtifactId(),
                version.sourceArtifactVersion(), version.sourceArtifactStatus(),
                version.createdBy(), version.createdAt());
    }

    private static ApprovalResponse toResponse(ApprovalRecord approval) {
        return new ApprovalResponse(approval.id(), approval.contentVersionId(),
                approval.actorId(), approval.comment(), approval.createdAt());
    }

    private static ReviewReportResponse toResponse(ReviewReport report) {
        return new ReviewReportResponse(report.id(), report.sourceArtifactId(),
                report.sourceArtifactVersion(), report.sourceArtifactStatus(), report.passed(),
                report.repairRound(), report.issues().stream()
                        .map(ContentController::toResponse).toList(), report.createdAt());
    }

    private static ReviewIssueResponse toResponse(ReviewIssue issue) {
        return new ReviewIssueResponse(issue.type().name(), issue.affectedTaskIds(),
                issue.message());
    }

    public record CreateVersionRequest(
            @Positive int expectedCurrentVersionNo,
            @PositiveOrZero long expectedItemVersion,
            @NotEmpty Map<String, Object> content,
            @NotNull List<String> sourceRefs) {}

    public record ApproveRequest(
            @Positive long contentVersionId,
            @Positive int expectedCurrentVersionNo,
            @PositiveOrZero long expectedItemVersion,
            String comment) {}

    public record RunContentsResponse(List<ContentResponse> contents,
                                      List<ReviewReportResponse> reviews) {}

    public record ContentResponse(long id, long runId, String taskId,
                                  int currentVersionNo, long itemVersion,
                                  Instant createdAt, Instant updatedAt,
                                  List<ContentVersionResponse> versions,
                                  List<ApprovalResponse> approvals) {}

    public record ContentVersionResponse(long id, int versionNo,
                                         Map<String, Object> content,
                                         List<String> sourceRefs,
                                         ContentOrigin origin,
                                         String sourceArtifactId,
                                         Integer sourceArtifactVersion,
                                         ArtifactStatus sourceArtifactStatus,
                                         Long createdBy, Instant createdAt) {}

    public record ApprovalResponse(long id, long contentVersionId, long actorUserId,
                                   String comment, Instant createdAt) {}

    public record ReviewReportResponse(long id, String sourceArtifactId,
                                       int sourceArtifactVersion,
                                       ArtifactStatus sourceArtifactStatus,
                                       boolean passed, int repairRound,
                                       List<ReviewIssueResponse> issues,
                                       Instant createdAt) {}

    public record ReviewIssueResponse(String type, Set<String> affectedTaskIds,
                                      String message) {}
}
