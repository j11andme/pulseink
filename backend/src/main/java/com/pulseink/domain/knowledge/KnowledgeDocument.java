package com.pulseink.domain.knowledge;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable knowledge document aggregate. The MySQL row is the business authority; Elasticsearch
 * only holds derived chunks. State transitions are strictly enforced:
 * PENDING → PROCESSING → ACTIVE, PENDING|PROCESSING → FAILED, FAILED → PENDING (retry).
 */
public final class KnowledgeDocument {

    private final long id;
    private final String sourceId;
    private final String originalFilename;
    private final String storageKey;
    private final String declaredMimeType;
    private String detectedMimeType;
    private final long sizeBytes;
    private final String checksumSha256;
    private final KnowledgeType knowledgeType;
    private final EvidenceAuthority authority;
    private final int documentVersion;
    private KnowledgeDocumentStatus status;
    private String embeddingProfileId;
    private String indexName;
    private int chunkCount;
    private String failureCode;
    private final long createdBy;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private KnowledgeDocument(long id, String sourceId, String originalFilename,
                              String storageKey, String declaredMimeType,
                              long sizeBytes, String checksumSha256,
                              KnowledgeType knowledgeType, EvidenceAuthority authority,
                              int documentVersion, KnowledgeDocumentStatus status,
                              long createdBy, long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.sourceId = requireNonBlank(sourceId, "sourceId");
        this.originalFilename = requireNonBlank(originalFilename, "originalFilename");
        this.storageKey = requireNonBlank(storageKey, "storageKey");
        this.declaredMimeType = requireNonBlank(declaredMimeType, "declaredMimeType");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = requireNonBlank(checksumSha256, "checksumSha256");
        this.knowledgeType = Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        if (documentVersion <= 0) {
            throw new IllegalArgumentException("documentVersion must be positive");
        }
        this.documentVersion = documentVersion;
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (createdBy <= 0) {
            throw new IllegalArgumentException("createdBy must be positive");
        }
        this.createdBy = createdBy;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static KnowledgeDocument create(
            String sourceId, String originalFilename, String storageKey,
            String declaredMimeType, long sizeBytes, String checksumSha256,
            KnowledgeType knowledgeType, EvidenceAuthority authority, long createdBy) {
        var now = Instant.now();
        return new KnowledgeDocument(
                0L, sourceId, originalFilename, storageKey, declaredMimeType,
                sizeBytes, checksumSha256, knowledgeType, authority,
                1, KnowledgeDocumentStatus.PENDING, createdBy, 0L, now, now);
    }

    public static KnowledgeDocument materialize(
            long id, String sourceId, String originalFilename, String storageKey,
            String declaredMimeType, String detectedMimeType, long sizeBytes,
            String checksumSha256, KnowledgeType knowledgeType, EvidenceAuthority authority,
            int documentVersion, KnowledgeDocumentStatus status, String embeddingProfileId,
            String indexName, int chunkCount, String failureCode, long createdBy,
            long version, Instant createdAt, Instant updatedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        var document = new KnowledgeDocument(
                id, sourceId, originalFilename, storageKey, declaredMimeType,
                sizeBytes, checksumSha256, knowledgeType, authority,
                documentVersion, status, createdBy, version, createdAt, updatedAt);
        document.detectedMimeType = detectedMimeType;
        document.embeddingProfileId = embeddingProfileId;
        document.indexName = indexName;
        document.chunkCount = chunkCount;
        document.failureCode = failureCode;
        return document;
    }

    public void markProcessing() {
        requireStatus(KnowledgeDocumentStatus.PENDING, "only PENDING documents can start processing");
        status = KnowledgeDocumentStatus.PROCESSING;
    }

    public void markActive(String detectedMimeType, String embeddingProfileId,
                           String indexName, int chunkCount) {
        requireStatus(KnowledgeDocumentStatus.PROCESSING, "only PROCESSING documents can become ACTIVE");
        if (detectedMimeType == null || detectedMimeType.isBlank()) {
            throw new IllegalArgumentException("detectedMimeType must not be blank");
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must not be negative");
        }
        this.detectedMimeType = detectedMimeType;
        this.embeddingProfileId = embeddingProfileId;
        this.indexName = indexName;
        this.chunkCount = chunkCount;
        this.failureCode = null;
        this.status = KnowledgeDocumentStatus.ACTIVE;
    }

    public void markFailed(String failureCode) {
        if (status != KnowledgeDocumentStatus.PENDING
                && status != KnowledgeDocumentStatus.PROCESSING) {
            throw new IllegalStateException(
                    "only PENDING or PROCESSING documents can fail, was " + status);
        }
        this.failureCode = requireNonBlank(failureCode, "failureCode");
        this.status = KnowledgeDocumentStatus.FAILED;
    }

    public void retry() {
        requireStatus(KnowledgeDocumentStatus.FAILED, "only FAILED documents can be retried");
        this.failureCode = null;
        this.status = KnowledgeDocumentStatus.PENDING;
    }

    private void requireStatus(KnowledgeDocumentStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + ", was " + status);
        }
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    public long id() { return id; }
    public String sourceId() { return sourceId; }
    public String originalFilename() { return originalFilename; }
    public String storageKey() { return storageKey; }
    public String declaredMimeType() { return declaredMimeType; }
    public String detectedMimeType() { return detectedMimeType; }
    public long sizeBytes() { return sizeBytes; }
    public String checksumSha256() { return checksumSha256; }
    public KnowledgeType knowledgeType() { return knowledgeType; }
    public EvidenceAuthority authority() { return authority; }
    public int documentVersion() { return documentVersion; }
    public KnowledgeDocumentStatus status() { return status; }
    public String embeddingProfileId() { return embeddingProfileId; }
    public String indexName() { return indexName; }
    public int chunkCount() { return chunkCount; }
    public String failureCode() { return failureCode; }
    public long createdBy() { return createdBy; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
