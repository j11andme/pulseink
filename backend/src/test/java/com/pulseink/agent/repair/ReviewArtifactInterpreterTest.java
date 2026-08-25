package com.pulseink.agent.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.agent.plan.PlanTaskAccess;
import com.pulseink.domain.content.ReviewIssueType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewArtifactInterpreterTest {

    private final ReviewArtifactInterpreter interpreter =
            new StrictReviewArtifactInterpreter();

    @Test
    void acceptsPassedReviewWithNoIssues() {
        var assessment = interpreter.interpret(review(Map.of(
                "passed", true,
                "issues", List.of())), plan());

        assertThat(assessment.passed()).isTrue();
        assertThat(assessment.issues()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ReviewIssueType.class)
    void acceptsEverySupportedIssueType(ReviewIssueType type) {
        var issue = type == ReviewIssueType.PLAN_GAP
                || type == ReviewIssueType.REPEATED_FAIL
                ? Map.<String, Object>of(
                        "type", type.name(),
                        "affectedTaskIds", List.of(),
                        "message", " plan must change ")
                : Map.<String, Object>of(
                        "type", type.name(),
                        "affectedTaskIds", List.of("create-b", "create-a", "create-a"),
                        "message", " repair draft ");

        var assessment = interpreter.interpret(review(Map.of(
                "passed", false,
                "issues", List.of(issue))), plan());

        assertThat(assessment.passed()).isFalse();
        assertThat(assessment.issues()).singleElement().satisfies(parsed -> {
            assertThat(parsed.type()).isEqualTo(type);
            assertThat(parsed.message()).doesNotStartWith(" ").doesNotEndWith(" ");
            if (type == ReviewIssueType.PLAN_GAP
                    || type == ReviewIssueType.REPEATED_FAIL) {
                assertThat(parsed.affectedTaskIds()).isEmpty();
            } else {
                assertThat(parsed.affectedTaskIds()).containsExactly("create-a", "create-b");
            }
        });
    }

    @Test
    void rejectsContradictoryUnknownOrOutOfPlanReviewData() {
        assertThatThrownBy(() -> interpreter.interpret(review(Map.of(
                "passed", true,
                "issues", List.of(Map.of(
                        "type", "STYLE", "affectedTaskIds", List.of("create-a"),
                        "message", "bad")))), plan()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> interpreter.interpret(review(Map.of(
                "passed", false,
                "issues", List.of(Map.of(
                        "type", "UNKNOWN", "affectedTaskIds", List.of("create-a"),
                        "message", "bad")))), plan()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> interpreter.interpret(review(Map.of(
                "passed", false,
                "issues", List.of(Map.of(
                        "type", "STYLE", "affectedTaskIds", List.of("missing"),
                        "message", "bad")))), plan()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> interpreter.interpret(review(Map.of(
                "passed", true, "issues", List.of(), "extra", "forbidden")), plan()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentArtifact review(Map<String, Object> content) {
        return AgentArtifact.create("review-1", 1L, "review", ArtifactType.REVIEW_REPORT,
                1, content, List.of(), Instant.parse("2026-08-13T00:00:00Z"));
    }

    static PlanSpec plan() {
        return new PlanSpec(PlanSpec.SUPPORTED_SCHEMA_VERSION, List.of(
                task("research-a", AgentRole.RESEARCHER, List.of(), ArtifactType.EVIDENCE_PACK),
                task("research-b", AgentRole.RESEARCHER, List.of(), ArtifactType.EVIDENCE_PACK),
                task("strategy-a", AgentRole.STRATEGIST, List.of("research-a"),
                        ArtifactType.CONTENT_STRATEGY),
                task("strategy-b", AgentRole.STRATEGIST, List.of("research-b"),
                        ArtifactType.CONTENT_STRATEGY),
                task("create-a", AgentRole.CREATOR, List.of("strategy-a"),
                        ArtifactType.CONTENT_DRAFT),
                task("create-b", AgentRole.CREATOR, List.of("strategy-b"),
                        ArtifactType.CONTENT_DRAFT),
                task("review", AgentRole.REVIEWER, List.of("create-a", "create-b"),
                        ArtifactType.REVIEW_REPORT)));
    }

    private static PlanTask task(String id, AgentRole role, List<String> dependencies,
                                 ArtifactType output) {
        return new PlanTask(id, role, id, dependencies, Set.of(), output,
                PlanTaskAccess.READ_ONLY);
    }
}
