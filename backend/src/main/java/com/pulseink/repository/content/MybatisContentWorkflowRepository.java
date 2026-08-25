package com.pulseink.repository.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.domain.content.ApprovalRecord;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentOrigin;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewIssue;
import com.pulseink.domain.content.ReviewIssueType;
import com.pulseink.domain.content.ReviewReport;
import com.pulseink.service.content.ContentWorkflowRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisContentWorkflowRepository implements ContentWorkflowRepository {

    private static final TypeReference<Map<String, Object>> CONTENT_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> SOURCE_REFS_TYPE = new TypeReference<>() {};

    private final ContentWorkflowMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisContentWorkflowRepository(ContentWorkflowMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional
    public void captureAgentVersion(long runId, String taskId, AgentArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        if (artifact.type() != ArtifactType.CONTENT_DRAFT) {
            throw new IllegalArgumentException("only CONTENT_DRAFT can become a content version");
        }
        if (artifact.runId() != runId || !artifact.taskId().equals(taskId)) {
            throw new IllegalArgumentException("artifact does not belong to the requested run/task");
        }
        if (mapper.findVersionByArtifactId(artifact.artifactId()) != null) {
            return;
        }

        var item = mapper.findItemByRunAndTask(runId, taskId);
        if (item == null) {
            item = new ContentItemEntity();
            item.setRunId(runId);
            item.setTaskId(taskId);
            mapper.insertItem(item);
        }

        var version = new ContentVersionEntity();
        version.setContentItemId(item.getId());
        version.setVersionNo(artifact.artifactVersion());
        version.setContentJson(writeJson(artifact.content()));
        version.setSourceRefsJson(writeJson(artifact.sourceRefs()));
        version.setOrigin(ContentOrigin.AGENT.name());
        version.setSourceArtifactId(artifact.artifactId());
        version.setSourceArtifactVersion(artifact.artifactVersion());
        version.setSourceArtifactStatus(artifact.status().name());
        mapper.insertVersion(version);
        if (artifact.status() == ArtifactStatus.VALID) {
            mapper.advanceAgentVersion(item.getId(), artifact.artifactVersion());
        }
    }

    @Override
    @Transactional
    public void captureReview(long runId, AgentArtifact artifact,
                              ReviewAssessment assessment, int repairRound) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        Objects.requireNonNull(assessment, "assessment must not be null");
        if (artifact.type() != ArtifactType.REVIEW_REPORT || artifact.runId() != runId) {
            throw new IllegalArgumentException("artifact is not a review for the requested run");
        }
        if (repairRound < 0) {
            throw new IllegalArgumentException("repairRound must not be negative");
        }
        if (mapper.findReviewByArtifactId(artifact.artifactId()) != null) {
            return;
        }

        var report = new ReviewReportEntity();
        report.setRunId(runId);
        report.setSourceArtifactId(artifact.artifactId());
        report.setSourceArtifactVersion(artifact.artifactVersion());
        report.setSourceArtifactStatus(artifact.status().name());
        report.setPassed(assessment.passed());
        report.setRepairRound(repairRound);
        mapper.insertReview(report);

        for (int issueIndex = 0; issueIndex < assessment.issues().size(); issueIndex++) {
            var issue = assessment.issues().get(issueIndex);
            if (issue.affectedTaskIds().isEmpty()) {
                mapper.insertIssue(issueEntity(report.getId(), issueIndex, issue, null));
            } else {
                for (var taskId : issue.affectedTaskIds()) {
                    mapper.insertIssue(issueEntity(
                            report.getId(), issueIndex, issue, taskId));
                }
            }
        }
    }

    @Override
    public List<ContentItem> findByRunId(long runId) {
        return mapper.findItemsByRunId(runId).stream().map(this::toItem).toList();
    }

    @Override
    public Optional<ContentItem> findById(long contentId) {
        return Optional.ofNullable(mapper.findItemById(contentId)).map(this::toItem);
    }

    @Override
    @Transactional
    public ContentVersion appendHumanVersion(long contentId, int expectedCurrentVersionNo,
                                             long expectedItemVersion,
                                             Map<String, Object> content,
                                             List<String> sourceRefs, long actorId) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(sourceRefs, "sourceRefs must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        int nextVersion = Math.addExact(expectedCurrentVersionNo, 1);
        if (mapper.appendVersionCas(contentId, expectedCurrentVersionNo,
                expectedItemVersion, nextVersion) != 1) {
            throw new IllegalStateException("stale content item " + contentId);
        }

        var version = new ContentVersionEntity();
        version.setContentItemId(contentId);
        version.setVersionNo(nextVersion);
        version.setContentJson(writeJson(content));
        version.setSourceRefsJson(writeJson(sourceRefs));
        version.setOrigin(ContentOrigin.HUMAN.name());
        version.setCreatedBy(actorId);
        mapper.insertVersion(version);
        return toVersion(requireVersion(version.getId()));
    }

    @Override
    @Transactional
    public ApprovalRecord approve(long contentId, long contentVersionId,
                                  int expectedCurrentVersionNo, long expectedItemVersion,
                                  String comment, long actorId) {
        String normalized = comment == null ? "" : comment.strip();
        if (normalized.codePointCount(0, normalized.length()) > 1_000) {
            throw new IllegalArgumentException("approval comment exceeds 1000 code points");
        }
        if (mapper.reserveApprovalCas(contentId, contentVersionId,
                expectedCurrentVersionNo, expectedItemVersion) != 1) {
            throw new IllegalStateException("content version is stale, invalidated, or already approved");
        }
        var approval = new ApprovalRecordEntity();
        approval.setContentVersionId(contentVersionId);
        approval.setActorId(actorId);
        approval.setCommentText(normalized);
        mapper.insertApproval(approval);
        return toApproval(mapper.findApprovalById(approval.getId()));
    }

    @Override
    public List<ReviewReport> findReviewsByRunId(long runId) {
        return mapper.findReviewsByRunId(runId).stream().map(this::toReport).toList();
    }

    private ContentItem toItem(ContentItemEntity entity) {
        var versions = mapper.findVersionsByItemId(entity.getId()).stream()
                .map(this::toVersion).toList();
        var approvals = mapper.findApprovalsByItemId(entity.getId()).stream()
                .map(this::toApproval).toList();
        return new ContentItem(entity.getId(), entity.getRunId(), entity.getTaskId(),
                entity.getCurrentVersionNo(), entity.getVersion(), entity.getCreatedAt(),
                entity.getUpdatedAt(), versions, approvals);
    }

    private ContentVersion toVersion(ContentVersionEntity entity) {
        return new ContentVersion(entity.getId(), entity.getContentItemId(), entity.getVersionNo(),
                readJson(entity.getContentJson(), CONTENT_TYPE),
                readJson(entity.getSourceRefsJson(), SOURCE_REFS_TYPE),
                ContentOrigin.valueOf(entity.getOrigin()), entity.getSourceArtifactId(),
                entity.getSourceArtifactVersion(), enumOrNull(entity.getSourceArtifactStatus()),
                entity.getCreatedBy(), entity.getCreatedAt());
    }

    private ApprovalRecord toApproval(ApprovalRecordEntity entity) {
        return new ApprovalRecord(entity.getId(), entity.getContentVersionId(),
                entity.getActorId(), entity.getCommentText(), entity.getCreatedAt());
    }

    private ReviewReport toReport(ReviewReportEntity entity) {
        var grouped = new LinkedHashMap<String, IssueAccumulator>();
        for (var row : mapper.findIssuesByReportId(entity.getId())) {
            String key = String.valueOf(row.getIssueIndex());
            var accumulator = grouped.computeIfAbsent(key, ignored ->
                    new IssueAccumulator(ReviewIssueType.valueOf(row.getIssueType()),
                            row.getMessage()));
            if (row.getAffectedTaskId() != null) {
                accumulator.taskIds.add(row.getAffectedTaskId());
            }
        }
        var issues = new ArrayList<ReviewIssue>();
        for (var accumulator : grouped.values()) {
            issues.add(new ReviewIssue(accumulator.type, accumulator.taskIds,
                    accumulator.message));
        }
        return new ReviewReport(entity.getId(), entity.getRunId(), entity.getSourceArtifactId(),
                entity.getSourceArtifactVersion(),
                ArtifactStatus.valueOf(entity.getSourceArtifactStatus()), entity.getPassed(),
                entity.getRepairRound(), issues, entity.getCreatedAt());
    }

    private ReviewIssueEntity issueEntity(long reportId, int issueIndex,
                                          ReviewIssue issue, String taskId) {
        var entity = new ReviewIssueEntity();
        entity.setReviewReportId(reportId);
        entity.setIssueIndex(issueIndex);
        entity.setIssueType(issue.type().name());
        entity.setAffectedTaskId(taskId);
        entity.setMessage(issue.message());
        return entity;
    }

    private ContentVersionEntity requireVersion(long id) {
        var version = mapper.findVersionById(id);
        if (version == null) {
            throw new IllegalStateException("content version insert was not readable");
        }
        return version;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("content cannot be serialized", exception);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored content JSON is invalid", exception);
        }
    }

    private ArtifactStatus enumOrNull(String value) {
        return value == null ? null : ArtifactStatus.valueOf(value);
    }

    private static final class IssueAccumulator {
        private final ReviewIssueType type;
        private final String message;
        private final TreeSet<String> taskIds = new TreeSet<>();

        private IssueAccumulator(ReviewIssueType type, String message) {
            this.type = type;
            this.message = message;
        }
    }
}
