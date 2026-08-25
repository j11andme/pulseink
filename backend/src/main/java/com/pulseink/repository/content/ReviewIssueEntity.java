package com.pulseink.repository.content;

public class ReviewIssueEntity {
    private Long id;
    private Long reviewReportId;
    private Integer issueIndex;
    private String issueType;
    private String affectedTaskId;
    private String message;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReviewReportId() { return reviewReportId; }
    public void setReviewReportId(Long reviewReportId) { this.reviewReportId = reviewReportId; }
    public Integer getIssueIndex() { return issueIndex; }
    public void setIssueIndex(Integer issueIndex) { this.issueIndex = issueIndex; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getAffectedTaskId() { return affectedTaskId; }
    public void setAffectedTaskId(String affectedTaskId) { this.affectedTaskId = affectedTaskId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
