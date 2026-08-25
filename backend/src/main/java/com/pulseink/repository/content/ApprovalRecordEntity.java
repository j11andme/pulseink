package com.pulseink.repository.content;

import java.time.Instant;

public class ApprovalRecordEntity {
    private Long id;
    private Long contentVersionId;
    private Long actorId;
    private String commentText;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContentVersionId() { return contentVersionId; }
    public void setContentVersionId(Long contentVersionId) { this.contentVersionId = contentVersionId; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getCommentText() { return commentText; }
    public void setCommentText(String commentText) { this.commentText = commentText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
