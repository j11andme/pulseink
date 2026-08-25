package com.pulseink.agent.repair;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewIssue;
import com.pulseink.domain.content.ReviewIssueType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Strictly converts untrusted REVIEW_REPORT content into a domain assessment. */
public final class StrictReviewArtifactInterpreter implements ReviewArtifactInterpreter {

    private static final Set<String> ROOT_FIELDS = Set.of("passed", "issues");
    private static final Set<String> ISSUE_FIELDS =
            Set.of("type", "affectedTaskIds", "message");

    @Override
    public ReviewAssessment interpret(AgentArtifact reviewArtifact, PlanSpec plan) {
        if (reviewArtifact == null || plan == null) {
            throw new IllegalArgumentException("review artifact and plan must not be null");
        }
        if (reviewArtifact.type() != ArtifactType.REVIEW_REPORT
                || reviewArtifact.status() != ArtifactStatus.VALID) {
            throw new IllegalArgumentException("artifact must be a VALID REVIEW_REPORT");
        }
        Map<String, Object> content = reviewArtifact.content();
        requireExactFields(content, ROOT_FIELDS, "review");
        if (!(content.get("passed") instanceof Boolean passed)) {
            throw new IllegalArgumentException("review passed must be boolean");
        }
        if (!(content.get("issues") instanceof List<?> rawIssues)) {
            throw new IllegalArgumentException("review issues must be an array");
        }
        if (rawIssues.size() > 20) {
            throw new IllegalArgumentException("review issues exceed 20");
        }

        var creatorTaskIds = new HashSet<String>();
        for (var task : plan.tasks()) {
            if (task.role() == AgentRole.CREATOR) {
                creatorTaskIds.add(task.taskId());
            }
        }
        var issues = new ArrayList<ReviewIssue>();
        for (var rawIssue : rawIssues) {
            if (!(rawIssue instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("review issue must be an object");
            }
            var issueMap = stringKeyMap(rawMap);
            requireExactFields(issueMap, ISSUE_FIELDS, "review issue");
            var type = parseType(issueMap.get("type"));
            var affected = parseAffected(issueMap.get("affectedTaskIds"), creatorTaskIds);
            boolean planLevel = type == ReviewIssueType.PLAN_GAP
                    || type == ReviewIssueType.REPEATED_FAIL;
            if (!planLevel && affected.isEmpty()) {
                throw new IllegalArgumentException(type + " must affect at least one creator task");
            }
            if (!(issueMap.get("message") instanceof String message)) {
                throw new IllegalArgumentException("review issue message must be a string");
            }
            issues.add(new ReviewIssue(type, affected, message));
        }
        return new ReviewAssessment(passed, issues);
    }

    private static ReviewIssueType parseType(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("review issue type must be a string");
        }
        try {
            return ReviewIssueType.valueOf(text);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown review issue type: " + text, unknown);
        }
    }

    private static Set<String> parseAffected(Object value, Set<String> creators) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("affectedTaskIds must be an array");
        }
        var affected = new TreeSet<String>();
        for (var item : values) {
            if (!(item instanceof String taskId) || taskId.isBlank()) {
                throw new IllegalArgumentException("affected taskId must be a non-blank string");
            }
            String normalized = taskId.strip();
            if (!creators.contains(normalized)) {
                throw new IllegalArgumentException("unknown affected creator taskId: " + normalized);
            }
            affected.add(normalized);
        }
        return affected;
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("review issue fields must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireExactFields(Map<String, Object> value, Set<String> expected,
                                           String label) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException(label + " must contain exactly " + expected);
        }
    }
}
