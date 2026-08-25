package com.pulseink.service.content;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.domain.content.ApprovalRecord;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewReport;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ContentWorkflowRepository {

    void captureAgentVersion(long runId, String taskId, AgentArtifact artifact);

    void captureReview(long runId, AgentArtifact artifact,
                       ReviewAssessment assessment, int repairRound);

    List<ContentItem> findByRunId(long runId);

    Optional<ContentItem> findById(long contentId);

    ContentVersion appendHumanVersion(long contentId, int expectedCurrentVersionNo,
                                      long expectedItemVersion, Map<String, Object> content,
                                      List<String> sourceRefs, long actorId);

    ApprovalRecord approve(long contentId, long contentVersionId,
                           int expectedCurrentVersionNo, long expectedItemVersion,
                           String comment, long actorId);

    List<ReviewReport> findReviewsByRunId(long runId);
}
