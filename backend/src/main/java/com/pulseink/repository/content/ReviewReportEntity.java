package com.pulseink.repository.content;

import java.time.Instant;

public class ReviewReportEntity {
    private Long id;
    private Long runId;
    private String sourceArtifactId;
    private Integer sourceArtifactVersion;
    private String sourceArtifactStatus;
    private Boolean passed;
    private Integer repairRound;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(String sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }
    public Integer getSourceArtifactVersion() { return sourceArtifactVersion; }
    public void setSourceArtifactVersion(Integer sourceArtifactVersion) { this.sourceArtifactVersion = sourceArtifactVersion; }
    public String getSourceArtifactStatus() { return sourceArtifactStatus; }
    public void setSourceArtifactStatus(String sourceArtifactStatus) { this.sourceArtifactStatus = sourceArtifactStatus; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public Integer getRepairRound() { return repairRound; }
    public void setRepairRound(Integer repairRound) { this.repairRound = repairRound; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
