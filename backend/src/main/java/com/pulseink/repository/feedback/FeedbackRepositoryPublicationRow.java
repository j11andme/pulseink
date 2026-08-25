package com.pulseink.repository.feedback;

/**
 * Minimal publication row projection used by feedback ingestion to verify the target
 * publication exists and to resolve its run.
 */
public class FeedbackRepositoryPublicationRow {

    private Long id;
    private Long runId;

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
}
