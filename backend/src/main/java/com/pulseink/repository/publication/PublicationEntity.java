package com.pulseink.repository.publication;

import java.time.Instant;

/**
 * Persistence row for the {@code publication} table. Plain data carrier; domain mapping lives in
 * {@link PublicationMappings}.
 */
public class PublicationEntity {

    private Long id;
    private Long runId;
    private Long contentVersionId;
    private Long approvalRecordId;
    private Long requestedBy;
    private String channel;
    private String idempotencyKey;
    private String status;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private Long version;
    private String externalPostId;
    private String receiptJson;
    private String failureCode;
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getContentVersionId() {
        return contentVersionId;
    }

    public void setContentVersionId(Long contentVersionId) {
        this.contentVersionId = contentVersionId;
    }

    public Long getApprovalRecordId() {
        return approvalRecordId;
    }

    public void setApprovalRecordId(Long approvalRecordId) {
        this.approvalRecordId = approvalRecordId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getExternalPostId() {
        return externalPostId;
    }

    public void setExternalPostId(String externalPostId) {
        this.externalPostId = externalPostId;
    }

    public String getReceiptJson() {
        return receiptJson;
    }

    public void setReceiptJson(String receiptJson) {
        this.receiptJson = receiptJson;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
