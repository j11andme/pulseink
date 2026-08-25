package com.pulseink.service.memory;

import com.pulseink.domain.campaign.CampaignChannel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministically ordered read-only projection of the existing campaign facts a candidate
 * insight may cite: approved exact ContentVersions, successful Publication receipts and daily
 * metrics, all scoped to one run. The sourceSnapshotHash covers the whole projection and makes
 * repeated requests for the same facts idempotent.
 */
public record InsightSourceSnapshot(
        long runId,
        long campaignId,
        List<ApprovedVersion> approvedVersions,
        List<PublishedPost> publications,
        List<MetricWindow> metrics,
        String sourceSnapshotHash) {

    public InsightSourceSnapshot {
        Objects.requireNonNull(sourceSnapshotHash, "sourceSnapshotHash must not be null");
        approvedVersions = List.copyOf(approvedVersions);
        publications = List.copyOf(publications);
        metrics = List.copyOf(metrics);
    }

    public boolean containsVersion(long contentVersionId) {
        return approvedVersions.stream()
                .anyMatch(version -> version.contentVersionId() == contentVersionId);
    }

    public boolean containsPublication(long publicationId) {
        return publications.stream()
                .anyMatch(post -> post.publicationId() == publicationId);
    }

    public boolean containsPublishedVersion(long publicationId, long contentVersionId) {
        return publications.stream()
                .anyMatch(post -> post.publicationId() == publicationId
                        && post.contentVersionId() == contentVersionId);
    }

    public boolean containsMetricWindow(long publicationId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            return false;
        }
        var dates = metrics.stream()
                .filter(metric -> metric.publicationId() == publicationId)
                .map(MetricWindow::metricDate)
                .sorted()
                .toList();
        return !dates.isEmpty()
                && dates.getFirst().equals(from)
                && dates.getLast().equals(to);
    }

    public record ApprovedVersion(
            long contentVersionId,
            int versionNo,
            Map<String, Object> content,
            String origin,
            Long editedBy,
            long approvedBy,
            String approvalComment,
            Instant approvedAt) {

        public ApprovedVersion {
            content = Map.copyOf(content);
        }

        public ApprovedVersion(long contentVersionId, int versionNo,
                               Map<String, Object> content) {
            this(contentVersionId, versionNo, content, "UNKNOWN", null,
                    0L, "", Instant.EPOCH);
        }
    }

    public record PublishedPost(
            long publicationId,
            long contentVersionId,
            CampaignChannel channel,
            UUID externalPostId,
            Instant publishedAt,
            Map<String, Object> receipt) {

        public PublishedPost {
            receipt = receipt == null ? Map.of() : Map.copyOf(receipt);
        }

        public PublishedPost(long publicationId, long contentVersionId,
                             CampaignChannel channel, UUID externalPostId,
                             Instant publishedAt) {
            this(publicationId, contentVersionId, channel, externalPostId,
                    publishedAt, Map.of());
        }
    }

    public record MetricWindow(
            long publicationId,
            LocalDate metricDate,
            long views,
            long clicks,
            long likes) {
    }
}
