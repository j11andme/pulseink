package com.pulseink.repository.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.memory.CampaignEpisodicMemory;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightSourceSnapshot;
import com.pulseink.service.memory.MemorySourceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only JOIN projection of the existing content/approval/publication/metric tables into
 * the Campaign Episodic Memory shape. No second storage is written; the deterministic
 * snapshot hash is derived from the canonical JSON of the projected facts.
 */
@Repository
public class MybatisMemorySourceRepository implements MemorySourceRepository {

    private static final TypeReference<Map<String, Object>> CONTENT_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MybatisMemorySourceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public InsightSourceSnapshot loadEligibleSnapshot(long runId) {
        Long campaignId = runCampaignId(runId);
        if (campaignId == null) {
            throw notReady("run " + runId + " was not found");
        }

        List<InsightSourceSnapshot.ApprovedVersion> versions = jdbc.query("""
                SELECT DISTINCT cv.id, cv.version_no, cv.content_json, cv.origin,
                                cv.created_by, ar.actor_id, ar.comment_text, ar.created_at
                FROM publication p
                JOIN content_version cv ON cv.id = p.content_version_id
                JOIN approval_record ar ON ar.id = p.approval_record_id
                    AND ar.content_version_id = cv.id
                WHERE p.run_id = ?
                  AND p.status = 'PUBLISHED'
                ORDER BY cv.id
                """, (resultSet, rowNum) -> new InsightSourceSnapshot.ApprovedVersion(
                resultSet.getLong(1), resultSet.getInt(2),
                readContent(resultSet.getString(3)), resultSet.getString(4),
                resultSet.getObject(5, Long.class), resultSet.getLong(6),
                resultSet.getString(7), resultSet.getTimestamp(8).toInstant()), runId);
        if (versions.isEmpty()) {
            throw notReady("run " + runId + " has no approved content version");
        }

        List<InsightSourceSnapshot.PublishedPost> posts = publishedPosts(runId);
        if (posts.isEmpty()) {
            throw notReady("run " + runId + " has no published post");
        }

        List<InsightSourceSnapshot.MetricWindow> metrics = metrics(runId);
        if (metrics.isEmpty()) {
            throw notReady("run " + runId + " has no recorded metrics");
        }

        String hash = sha256(canonicalJson(runId, campaignId, versions, posts, metrics));
        return new InsightSourceSnapshot(runId, campaignId, versions, posts, metrics, hash);
    }

    @Override
    public CampaignEpisodicMemory loadEpisode(long runId) {
        Long campaignId = runCampaignId(runId);
        if (campaignId == null) {
            return CampaignEpisodicMemory.empty(runId);
        }
        List<InsightSourceSnapshot.ApprovedVersion> versions = jdbc.query("""
                SELECT DISTINCT cv.id, cv.version_no, cv.content_json, cv.origin,
                                cv.created_by, ar.actor_id, ar.comment_text, ar.created_at
                FROM publication p
                JOIN content_version cv ON cv.id = p.content_version_id
                JOIN approval_record ar ON ar.id = p.approval_record_id
                    AND ar.content_version_id = cv.id
                WHERE p.run_id = ?
                  AND p.status = 'PUBLISHED'
                ORDER BY cv.id
                """, (resultSet, rowNum) -> new InsightSourceSnapshot.ApprovedVersion(
                resultSet.getLong(1), resultSet.getInt(2),
                readContent(resultSet.getString(3)), resultSet.getString(4),
                resultSet.getObject(5, Long.class), resultSet.getLong(6),
                resultSet.getString(7), resultSet.getTimestamp(8).toInstant()), runId);
        return new CampaignEpisodicMemory(campaignId, runId, versions,
                publishedPosts(runId), metrics(runId));
    }

    private Long runCampaignId(long runId) {
        return jdbc.query(
                "SELECT campaign_id FROM campaign_run WHERE id = ?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, runId);
    }

    private List<InsightSourceSnapshot.PublishedPost> publishedPosts(long runId) {
        return jdbc.query("""
                SELECT id, content_version_id, channel, external_post_id, published_at,
                       receipt_json
                FROM publication
                WHERE run_id = ? AND status = 'PUBLISHED'
                ORDER BY id
                """, (resultSet, rowNum) -> new InsightSourceSnapshot.PublishedPost(
                resultSet.getLong(1), resultSet.getLong(2),
                toChannel(resultSet.getString(3)),
                UUID.fromString(resultSet.getString(4)),
                resultSet.getTimestamp(5).toInstant(),
                readReceipt(resultSet.getString(6))), runId);
    }

    private List<InsightSourceSnapshot.MetricWindow> metrics(long runId) {
        return jdbc.query("""
                SELECT m.publication_id, m.metric_date, m.views, m.clicks, m.likes
                FROM content_metric_daily m
                JOIN publication p ON p.id = m.publication_id
                WHERE p.run_id = ?
                ORDER BY m.publication_id, m.metric_date
                """, (resultSet, rowNum) -> new InsightSourceSnapshot.MetricWindow(
                resultSet.getLong(1),
                resultSet.getDate(2).toLocalDate(),
                resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5)), runId);
    }

    private Map<String, Object> canonicalJson(
            long runId, long campaignId,
            List<InsightSourceSnapshot.ApprovedVersion> versions,
            List<InsightSourceSnapshot.PublishedPost> posts,
            List<InsightSourceSnapshot.MetricWindow> metrics) {
        var root = new LinkedHashMap<String, Object>();
        root.put("runId", runId);
        root.put("campaignId", campaignId);
        var versionsJson = new ArrayList<Map<String, Object>>();
        for (var version : versions) {
            var item = new LinkedHashMap<String, Object>();
            item.put("contentVersionId", version.contentVersionId());
            item.put("versionNo", version.versionNo());
            item.put("content", new TreeMap<>(version.content()));
            item.put("origin", version.origin());
            item.put("editedBy", version.editedBy());
            item.put("approvedBy", version.approvedBy());
            item.put("approvalComment", version.approvalComment());
            item.put("approvedAt", version.approvedAt().toString());
            versionsJson.add(item);
        }
        root.put("approvedVersions", versionsJson);
        var postsJson = new ArrayList<Map<String, Object>>();
        for (var post : posts) {
            var item = new LinkedHashMap<String, Object>();
            item.put("publicationId", post.publicationId());
            item.put("contentVersionId", post.contentVersionId());
            item.put("channel", post.channel().name());
            item.put("externalPostId", post.externalPostId().toString());
            item.put("publishedAt", post.publishedAt().toString());
            item.put("receipt", new TreeMap<>(post.receipt()));
            postsJson.add(item);
        }
        root.put("publications", postsJson);
        var metricsJson = new ArrayList<Map<String, Object>>();
        for (var metric : metrics) {
            var item = new LinkedHashMap<String, Object>();
            item.put("publicationId", metric.publicationId());
            item.put("metricDate", metric.metricDate().toString());
            item.put("views", metric.views());
            item.put("clicks", metric.clicks());
            item.put("likes", metric.likes());
            metricsJson.add(item);
        }
        root.put("metrics", metricsJson);
        return root;
    }

    private String sha256(Map<String, Object> canonical) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("insight snapshot cannot be normalized", exception);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private Map<String, Object> readContent(String json) {
        try {
            return objectMapper.readValue(json, CONTENT_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored content JSON is invalid", exception);
        }
    }

    private Map<String, Object> readReceipt(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, CONTENT_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored publication receipt JSON is invalid",
                    exception);
        }
    }

    private CampaignChannel toChannel(String value) {
        try {
            return CampaignChannel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "publication stored an unknown channel value: " + value, ex);
        }
    }

    private static InsightException notReady(String message) {
        return new InsightException(InsightErrorCode.INSIGHT_SOURCE_NOT_READY, message);
    }
}
