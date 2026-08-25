package com.pulseink.agent.repair;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewIssue;
import com.pulseink.domain.content.ReviewIssueType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RepairRouterTest {

    private final RepairRouter router = new RepairRouter();

    @ParameterizedTest
    @EnumSource(value = ReviewIssueType.class, names = {"STYLE", "FORMAT"})
    void routesPresentationIssuesToAffectedCreatorOnly(ReviewIssueType type) {
        var decision = route(type, Set.of("create-a"), 0, 2);

        assertThat(decision.path()).isEqualTo(RepairPath.CREATOR);
        assertThat(decision.rootTaskIds()).containsExactly("create-a");
        assertThat(decision.invalidatedTaskIds()).containsExactly("create-a", "review");
    }

    @Test
    void routesEvidenceAndStrategyIssuesFromTheCorrectAncestors() {
        var evidence = route(ReviewIssueType.MISSING_EVIDENCE, Set.of("create-a"), 0, 2);
        assertThat(evidence.path()).isEqualTo(RepairPath.RESEARCHER_TO_CREATOR);
        assertThat(evidence.rootTaskIds()).containsExactly("research-a");
        assertThat(evidence.invalidatedTaskIds())
                .containsExactly("create-a", "research-a", "review", "strategy-a");

        var strategy = route(ReviewIssueType.STRATEGY_MISMATCH,
                Set.of("create-b"), 0, 2);
        assertThat(strategy.path()).isEqualTo(RepairPath.STRATEGIST_TO_CREATOR);
        assertThat(strategy.rootTaskIds()).containsExactly("strategy-b");
        assertThat(strategy.invalidatedTaskIds())
                .containsExactly("create-b", "review", "strategy-b");
    }

    @Test
    void combinesMultipleIssuesWithoutInvalidatingUnrelatedBranch() {
        var assessment = new ReviewAssessment(false, List.of(
                new ReviewIssue(ReviewIssueType.STYLE, Set.of("create-b"), "style"),
                new ReviewIssue(ReviewIssueType.MISSING_EVIDENCE,
                        Set.of("create-a"), "evidence")));

        var decision = router.route(assessment,
                ReviewArtifactInterpreterTest.plan(), 0, 2);

        assertThat(decision.rootTaskIds()).containsExactly("create-b", "research-a");
        assertThat(decision.invalidatedTaskIds())
                .containsExactly("create-a", "create-b", "research-a", "review", "strategy-a");
        assertThat(decision.invalidatedTaskIds())
                .doesNotContain("research-b", "strategy-b");
    }

    @ParameterizedTest
    @EnumSource(value = ReviewIssueType.class, names = {"PLAN_GAP", "REPEATED_FAIL"})
    void planIssuesRequireReplanning(ReviewIssueType type) {
        var decision = route(type, Set.of(), 0, 2);

        assertThat(decision.path()).isEqualTo(RepairPath.PLANNER_REPLAN);
        assertThat(decision.requiresReplan()).isTrue();
        assertThat(decision.invalidatedTaskIds()).containsExactly(
                "create-a", "create-b", "research-a", "research-b", "review",
                "strategy-a", "strategy-b");
    }

    @Test
    void exhaustedOrDisabledRepairRequiresHuman() {
        assertThat(route(ReviewIssueType.STYLE, Set.of("create-a"), 2, 2).path())
                .isEqualTo(RepairPath.WAITING_HUMAN);
        assertThat(route(ReviewIssueType.STYLE, Set.of("create-a"), 0, 0).requiresHuman())
                .isTrue();
    }

    private RepairDecision route(ReviewIssueType type, Set<String> affected,
                                 int completedRounds, int maxRounds) {
        return router.route(new ReviewAssessment(false,
                        List.of(new ReviewIssue(type, affected, "message"))),
                ReviewArtifactInterpreterTest.plan(), completedRounds, maxRounds);
    }
}
