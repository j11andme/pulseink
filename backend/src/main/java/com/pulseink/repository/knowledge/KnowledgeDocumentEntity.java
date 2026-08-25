package com.pulseink.repository.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("source_id")
    private String sourceId;

    @TableField("original_filename")
    private String originalFilename;

    @TableField("storage_key")
    private String storageKey;

    @TableField("declared_mime_type")
    private String declaredMimeType;

    @TableField("detected_mime_type")
    private String detectedMimeType;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("checksum_sha256")
    private String checksumSha256;

    @TableField("knowledge_type")
    private String knowledgeType;

    private String authority;

    @TableField("document_version")
    private Integer documentVersion;

    private String status;

    @TableField("embedding_profile_id")
    private String embeddingProfileId;

    @TableField("index_name")
    private String indexName;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("failure_code")
    private String failureCode;

    @TableField("created_by")
    private Long createdBy;

    private Long version;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getDeclaredMimeType() { return declaredMimeType; }
    public void setDeclaredMimeType(String declaredMimeType) { this.declaredMimeType = declaredMimeType; }
    public String getDetectedMimeType() { return detectedMimeType; }
    public void setDetectedMimeType(String detectedMimeType) { this.detectedMimeType = detectedMimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }
    public String getKnowledgeType() { return knowledgeType; }
    public void setKnowledgeType(String knowledgeType) { this.knowledgeType = knowledgeType; }
    public String getAuthority() { return authority; }
    public void setAuthority(String authority) { this.authority = authority; }
    public Integer getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(Integer documentVersion) { this.documentVersion = documentVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEmbeddingProfileId() { return embeddingProfileId; }
    public void setEmbeddingProfileId(String embeddingProfileId) { this.embeddingProfileId = embeddingProfileId; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
