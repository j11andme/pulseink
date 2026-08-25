package com.pulseink.domain.memory;

import com.pulseink.domain.campaign.CampaignChannel;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable campaign insight with explicit business invariants. Only APPROVED rows may be
 * projected into the derived ES index; PENDING/REJECTED rows never become searchable memory.
 */
public record CampaignInsight(
        long id,
        long campaignId,
        long runId,
        InsightCategory category,
        String title,
        String insightText,
        InsightScopeType scopeType,
        String scopeValue,
        List<CampaignChannel> applicableChannels,
        List<InsightEvidenceRef> evidenceRefs,
        double confidence,
        List<String> limitations,
        String sourceSnapshotHash,
        String promptVersion,
        InsightStatus status,
        InsightIndexStatus indexStatus,
        int indexAttempts,
        Instant nextIndexAttemptAt,
        String lastIndexError,
        long createdBy,
        Long reviewedBy,
        String reviewComment,
        long version,
        Instant createdAt,
        Instant reviewedAt,
        Instant indexedAt) {

    private static final int MAX_TITLE_CODE_POINTS = 120;
    private static final int MAX_TEXT_CODE_POINTS = 2_000;
    private static final int MAX_SCOPE_VALUE_CODE_POINTS = 64;
    private static final int MAX_LIMITATIONS = 20;
    private static final int MAX_LIMITATION_CODE_POINTS = 500;
    private static final int MAX_COMMENT_CODE_POINTS = 1_000;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public CampaignInsight {
        if (id < 0 || campaignId <= 0 || runId <= 0 || createdBy <= 0 || version < 0) {
            throw new IllegalArgumentException("insight ids must be valid");
        }
        category = Objects.requireNonNull(category, "category must not be null");
        title = requireCappedText(title, "title", MAX_TITLE_CODE_POINTS, true);
        insightText = requireCappedText(insightText, "insightText", MAX_TEXT_CODE_POINTS, true);
        scopeType = Objects.requireNonNull(scopeType, "scopeType must not be null");
        if (scopeType == InsightScopeType.CHANNEL) {
            scopeValue = requireCappedText(scopeValue, "scopeValue",
                    MAX_SCOPE_VALUE_CODE_POINTS, true);
        } else {
            if (scopeValue != null && !scopeValue.isBlank()) {
                throw new IllegalArgumentException(
                        "WORKSPACE scope must not carry a scopeValue");
            }
            scopeValue = "";
        }
        applicableChannels = List.copyOf(Objects.requireNonNull(
                applicableChannels, "applicableChannels must not be null"));
        if (applicableChannels.isEmpty() || applicableChannels.size() > 3
                || Set.copyOf(applicableChannels).size() != applicableChannels.size()) {
            throw new IllegalArgumentException(
                    "applicableChannels must contain 1 to 3 distinct channels");
        }
        evidenceRefs = List.copyOf(Objects.requireNonNull(
                evidenceRefs, "evidenceRefs must not be null"));
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("evidenceRefs must not be empty");
        }
        if (evidenceRefs.size() > MAX_LIMITATIONS) {
            throw new IllegalArgumentException(
                    "evidenceRefs exceeds the limit of " + MAX_LIMITATIONS);
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]");
        }
        limitations = limitations == null
                ? List.of()
                : List.copyOf(limitations.stream()
                        .map(item -> requireCappedText(item, "limitation",
                                MAX_LIMITATION_CODE_POINTS, true))
                        .toList());
        if (limitations.size() > MAX_LIMITATIONS) {
            throw new IllegalArgumentException(
                    "limitations exceeds the limit of " + MAX_LIMITATIONS);
        }
        sourceSnapshotHash = Objects.requireNonNull(
                sourceSnapshotHash, "sourceSnapshotHash must not be null");
        if (!SHA256_HEX.matcher(sourceSnapshotHash).matches()) {
            throw new IllegalArgumentException(
                    "sourceSnapshotHash must be a 64 character SHA-256 hex digest");
        }
        promptVersion = requireCappedText(promptVersion, "promptVersion", 32, true);
        status = Objects.requireNonNull(status, "status must not be null");
        indexStatus = Objects.requireNonNull(indexStatus, "indexStatus must not be null");
        if (indexAttempts < 0) {
            throw new IllegalArgumentException("indexAttempts must not be negative");
        }
        if (lastIndexError != null
                && lastIndexError.codePointCount(0, lastIndexError.length()) > 1_024) {
            throw new IllegalArgumentException(
                    "lastIndexError exceeds 1024 code points");
        }
        if (reviewedBy != null && reviewedBy <= 0) {
            throw new IllegalArgumentException("reviewedBy must be positive when set");
        }
        reviewComment = reviewComment == null ? null
                : requireCappedText(reviewComment, "reviewComment",
                        MAX_COMMENT_CODE_POINTS, false);
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * New PENDING candidate: never searchable, never indexed, with a fresh version 0.
     */
    public static CampaignInsight pending(
            long campaignId,
            long runId,
            InsightCategory category,
            String title,
            String insightText,
            InsightScopeType scopeType,
            String scopeValue,
            List<CampaignChannel> applicableChannels,
            List<InsightEvidenceRef> evidenceRefs,
            double confidence,
            List<String> limitations,
            String sourceSnapshotHash,
            String promptVersion,
            long createdBy,
            Instant createdAt) {
        return new CampaignInsight(0L, campaignId, runId, category, title, insightText,
                scopeType, scopeValue, applicableChannels, evidenceRefs, confidence,
                limitations, sourceSnapshotHash, promptVersion, InsightStatus.PENDING,
                InsightIndexStatus.NOT_INDEXED, 0, null, null, createdBy, null, null, 0L,
                createdAt, null, null);
    }

    private static String requireCappedText(String value, String field,
                                            int maxCodePoints, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(field + " must not be null");
            }
            return null;
        }
        String normalized = value.strip();
        if (required && normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw new IllegalArgumentException(
                    field + " exceeds " + maxCodePoints + " code points");
        }
        return normalized;
    }
}
