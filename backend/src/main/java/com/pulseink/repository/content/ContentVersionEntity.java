package com.pulseink.repository.content;

import java.time.Instant;

public class ContentVersionEntity {
    private Long id;
    private Long contentItemId;
    private Integer versionNo;
    private String contentJson;
    private String sourceRefsJson;
    private String origin;
    private String sourceArtifactId;
    private Integer sourceArtifactVersion;
    private String sourceArtifactStatus;
    private Long createdBy;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContentItemId() { return contentItemId; }
    public void setContentItemId(Long contentItemId) { this.contentItemId = contentItemId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public void setSourceRefsJson(String sourceRefsJson) { this.sourceRefsJson = sourceRefsJson; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(String sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }
    public Integer getSourceArtifactVersion() { return sourceArtifactVersion; }
    public void setSourceArtifactVersion(Integer sourceArtifactVersion) { this.sourceArtifactVersion = sourceArtifactVersion; }
    public String getSourceArtifactStatus() { return sourceArtifactStatus; }
    public void setSourceArtifactStatus(String sourceArtifactStatus) { this.sourceArtifactStatus = sourceArtifactStatus; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
