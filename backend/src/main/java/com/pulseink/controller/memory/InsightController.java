package com.pulseink.controller.memory;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightEvidenceRef;
import com.pulseink.domain.memory.InsightIndexStatus;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.domain.memory.InsightStatus;
import com.pulseink.service.memory.ApprovedInsightHit;
import com.pulseink.service.memory.ConsolidateInsightUseCase;
import com.pulseink.service.memory.InsightDecision;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.QueryInsightUseCase;
import com.pulseink.service.memory.ReviewInsightUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Insight REST surface: user-triggered candidate generation, human decision and read/search.
 * Responses never expose embeddings, prompts, provider errors or internal keys.
 */
@RestController
@RequestMapping("/api")
public class InsightController {

    private final ConsolidateInsightUseCase consolidation;
    private final ReviewInsightUseCase review;
    private final QueryInsightUseCase query;

    public InsightController(ConsolidateInsightUseCase consolidation,
                             ReviewInsightUseCase review,
                             QueryInsightUseCase query) {
        this.consolidation = consolidation;
        this.review = review;
        this.query = query;
    }

    @PostMapping("/runs/{runId}/insight-candidates")
    public InsightResponse generateCandidate(@PathVariable long runId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return toResponse(consolidation.generateCandidate(runId, actorUserId(jwt)));
    }

    @GetMapping("/campaigns/{campaignId}/insights")
    public List<InsightResponse> listByCampaign(@PathVariable long campaignId) {
        return query.listByCampaign(campaignId).stream()
                .map(InsightController::toResponse).toList();
    }

    @PostMapping("/insights/{insightId}/decision")
    public InsightResponse decide(@PathVariable long insightId,
                                  @Valid @RequestBody DecisionRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return toResponse(review.decide(insightId, decision(request.decision()),
                request.comment(), actorUserId(jwt)));
    }

    @GetMapping("/insights/search")
    public List<SearchHitResponse> search(
            @RequestParam String query,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "0") int topK) {
        var hits = this.query.searchApproved(query, channel == null ? null
                : channel(channel), topK);
        return hits.stream().map(InsightController::toResponse).toList();
    }

    private static InsightDecision decision(String value) {
        try {
            return InsightDecision.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InsightException(InsightErrorCode.VALIDATION_ERROR,
                    "decision must be APPROVE or REJECT");
        }
    }

    private static CampaignChannel channel(String value) {
        try {
            return CampaignChannel.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InsightException(InsightErrorCode.VALIDATION_ERROR,
                    "unsupported channel: " + value);
        }
    }

    private static long actorUserId(Jwt jwt) {
        if (jwt != null && jwt.getClaim("uid") instanceof Number number
                && number.longValue() > 0) {
            return number.longValue();
        }
        throw new IllegalStateException("jwt uid claim is missing or invalid");
    }

    private static InsightResponse toResponse(CampaignInsight insight) {
        return new InsightResponse(
                insight.id(), insight.campaignId(), insight.runId(), insight.category(),
                insight.title(), insight.insightText(), insight.scopeType(),
                insight.scopeValue(), insight.applicableChannels(),
                insight.evidenceRefs().stream()
                        .map(ref -> new EvidenceRefResponse(ref.contentVersionId(),
                                ref.publicationId(), ref.metricFrom(), ref.metricTo()))
                        .toList(),
                insight.confidence(), insight.limitations(), insight.status(),
                insight.indexStatus(), insight.createdBy(), insight.reviewedBy(),
                insight.reviewComment(), insight.createdAt(), insight.reviewedAt(),
                insight.indexedAt());
    }

    private static SearchHitResponse toResponse(ApprovedInsightHit hit) {
        return new SearchHitResponse(hit.insightId(), hit.sourceCampaignId(), hit.title(),
                hit.insightText(), hit.category(), hit.scopeType(), hit.scopeValue(),
                hit.applicableChannels(), hit.confidence(), hit.approvedAt());
    }

    public record DecisionRequest(
            @NotBlank String decision,
            String comment) {
    }

    public record EvidenceRefResponse(long contentVersionId, long publicationId,
                                      LocalDate metricFrom, LocalDate metricTo) {
    }

    public record InsightResponse(
            long id,
            long campaignId,
            long runId,
            InsightCategory category,
            String title,
            String insightText,
            InsightScopeType scopeType,
            String scopeValue,
            List<CampaignChannel> applicableChannels,
            List<EvidenceRefResponse> evidenceRefs,
            double confidence,
            List<String> limitations,
            InsightStatus status,
            InsightIndexStatus indexStatus,
            long createdBy,
            Long reviewedBy,
            String reviewComment,
            Instant createdAt,
            Instant reviewedAt,
            Instant indexedAt) {
    }

    public record SearchHitResponse(
            long insightId,
            long sourceCampaignId,
            String title,
            String insightText,
            InsightCategory category,
            InsightScopeType scopeType,
            String scopeValue,
            List<CampaignChannel> applicableChannels,
            double confidence,
            Instant approvedAt) {
    }
}
