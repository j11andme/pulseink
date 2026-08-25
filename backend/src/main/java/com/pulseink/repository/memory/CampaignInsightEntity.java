package com.pulseink.repository.memory;

import java.time.Instant;

/**
 * Persistence row for {@code campaign_insight}. JSON columns are carried as raw strings and
 * converted by the repository so the domain stays framework-free.
 */
public class CampaignInsightEntity {

    private Long id;
    private Long campaignId;
    private Long runId;
    private String category;
    private String title;
    private String insightText;
    private String scopeType;
    private String scopeValue;
    private String applicableChannelsJson;
    private String evidenceRefsJson;
    private String limitationsJson;
    private Double confidence;
    private String sourceSnapshotHash;
    private String promptVersion;
    private String status;
    private String indexStatus;
    private Integer indexAttempts;
    private Instant nextIndexAttemptAt;
    private String lastIndexError;
    private Long createdBy;
    private Long reviewedBy;
    private String reviewComment;
    private Long version;
    private Instant createdAt;
    private Instant reviewedAt;
    private Instant indexedAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInsightText() {
        return insightText;
    }

    public void setInsightText(String insightText) {
        this.insightText = insightText;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeValue() {
        return scopeValue;
    }

    public void setScopeValue(String scopeValue) {
        this.scopeValue = scopeValue;
    }

    public String getApplicableChannelsJson() {
        return applicableChannelsJson;
    }

    public void setApplicableChannelsJson(String applicableChannelsJson) {
        this.applicableChannelsJson = applicableChannelsJson;
    }

    public String getEvidenceRefsJson() {
        return evidenceRefsJson;
    }

    public void setEvidenceRefsJson(String evidenceRefsJson) {
        this.evidenceRefsJson = evidenceRefsJson;
    }

    public String getLimitationsJson() {
        return limitationsJson;
    }

    public void setLimitationsJson(String limitationsJson) {
        this.limitationsJson = limitationsJson;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getSourceSnapshotHash() {
        return sourceSnapshotHash;
    }

    public void setSourceSnapshotHash(String sourceSnapshotHash) {
        this.sourceSnapshotHash = sourceSnapshotHash;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(String indexStatus) {
        this.indexStatus = indexStatus;
    }

    public Integer getIndexAttempts() {
        return indexAttempts;
    }

    public void setIndexAttempts(Integer indexAttempts) {
        this.indexAttempts = indexAttempts;
    }

    public Instant getNextIndexAttemptAt() {
        return nextIndexAttemptAt;
    }

    public void setNextIndexAttemptAt(Instant nextIndexAttemptAt) {
        this.nextIndexAttemptAt = nextIndexAttemptAt;
    }

    public String getLastIndexError() {
        return lastIndexError;
    }

    public void setLastIndexError(String lastIndexError) {
        this.lastIndexError = lastIndexError;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
