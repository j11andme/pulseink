package com.pulseink.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator(12);

    private PlanTask task(String id, AgentRole role, ArtifactType output,
                          String... dependsOn) {
        return new PlanTask(id, role, "objective-" + id,
                List.of(dependsOn), Set.of(), output, PlanTaskAccess.READ_ONLY);
    }

    private PlanTask taskWithRequired(String id, AgentRole role, ArtifactType output,
                                      Set<ArtifactType> required, String... dependsOn) {
        return new PlanTask(id, role, "objective-" + id,
                List.of(dependsOn), required, output, PlanTaskAccess.READ_ONLY);
    }

    @Test
    void minimalStrategistToCreatorPlanProducesTwoStages() {
        var plan = new PlanSpec(1, List.of(
                task("strategy", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("create", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "strategy")));

        var stages = validator.validate(plan, Set.of());

        assertThat(stages).hasSize(2);
        assertThat(stages.get(0)).extracting(PlanTask::taskId).containsExactly("strategy");
        assertThat(stages.get(1)).extracting(PlanTask::taskId).containsExactly("create");
    }

    @Test
    void fiveRoleHighRiskPlanValidates() {
        var plan = new PlanSpec(1, List.of(
                task("research-1", AgentRole.RESEARCHER, ArtifactType.EVIDENCE_PACK),
                task("research-2", AgentRole.RESEARCHER, ArtifactType.EVIDENCE_PACK),
                task("strategy", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY,
                        "research-1", "research-2"),
                task("create-blog", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "strategy"),
                task("create-social", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "strategy"),
                task("review", AgentRole.REVIEWER, ArtifactType.REVIEW_REPORT,
                        "create-blog", "create-social")));

        var stages = validator.validate(plan, Set.of());

        assertThat(stages).hasSize(4);
        assertThat(stages.get(0)).extracting(PlanTask::taskId)
                .containsExactly("research-1", "research-2");
        assertThat(stages.get(1)).extracting(PlanTask::taskId).containsExactly("strategy");
        assertThat(stages.get(2)).extracting(PlanTask::taskId)
                .containsExactly("create-blog", "create-social");
        assertThat(stages.get(3)).extracting(PlanTask::taskId).containsExactly("review");
    }

    @Test
    void independentResearchersShareAStageSortedByTaskId() {
        var plan = new PlanSpec(1, List.of(
                task("research-b", AgentRole.RESEARCHER, ArtifactType.EVIDENCE_PACK),
                task("research-a", AgentRole.RESEARCHER, ArtifactType.EVIDENCE_PACK),
                task("strategy", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY,
                        "research-a", "research-b"),
                task("create", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "strategy")));

        var stages = validator.validate(plan, Set.of());

        assertThat(stages.get(0)).extracting(PlanTask::taskId)
                .containsExactly("research-a", "research-b");
    }

    @Test
    void multiChannelCreatorsShareAStage() {
        var plan = new PlanSpec(1, List.of(
                task("strategy", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("create-social", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "strategy"),
                task("create-blog", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "strategy")));

        var stages = validator.validate(plan, Set.of());

        assertThat(stages.get(1)).extracting(PlanTask::taskId)
                .containsExactly("create-blog", "create-social");
    }

    @Test
    void rejectsCycleAndSelfCycle() {
        var cycle = new PlanSpec(1, List.of(
                task("a", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY, "b"),
                task("b", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY, "a"),
                task("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "a")));
        assertThatThrownBy(() -> validator.validate(cycle, Set.of()))
                .hasMessageContaining("cycle");

        var self = new PlanSpec(1, List.of(
                task("a", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY, "a"),
                task("b", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "a")));
        assertThatThrownBy(() -> validator.validate(self, Set.of()))
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsUnknownAndDuplicateDependencies() {
        var unknown = new PlanSpec(1, List.of(
                task("a", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("b", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "missing")));
        assertThatThrownBy(() -> validator.validate(unknown, Set.of()))
                .hasMessageContaining("unknown");

        var duplicateDep = new PlanSpec(1, List.of(
                task("a", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                new PlanTask("b", AgentRole.CREATOR, "o", List.of("a", "a"), Set.of(),
                        ArtifactType.CONTENT_DRAFT, PlanTaskAccess.READ_ONLY)));
        assertThatThrownBy(() -> validator.validate(duplicateDep, Set.of()))
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsDuplicateTaskId() {
        var plan = new PlanSpec(1, List.of(
                task("a", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("a", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "a")));
        assertThatThrownBy(() -> validator.validate(plan, Set.of()))
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsInvalidTaskIds() {
        assertThatThrownBy(() -> new PlanTask("Bad_ID", AgentRole.STRATEGIST, "o", List.of(),
                Set.of(), ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> new PlanTask("1starts-with-digit", AgentRole.STRATEGIST, "o",
                List.of(), Set.of(), ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPlannerRoleAndSideEffectTasks() {
        var planner = new PlanSpec(1, List.of(
                new PlanTask("p", AgentRole.PLANNER, "o", List.of(), Set.of(),
                        ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY),
                task("b", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "p")));
        assertThatThrownBy(() -> validator.validate(planner, Set.of()))
                .hasMessageContaining("PLANNER");

        var sideEffect = new PlanSpec(1, List.of(
                new PlanTask("s", AgentRole.STRATEGIST, "o", List.of(), Set.of(),
                        ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.SIDE_EFFECT),
                task("b", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "s")));
        assertThatThrownBy(() -> validator.validate(sideEffect, Set.of()))
                .hasMessageContaining("SIDE_EFFECT");
    }

    @Test
    void rejectsWrongRoleOutputPairings() {
        var researcher = new PlanSpec(1, List.of(
                task("r", AgentRole.RESEARCHER, ArtifactType.CONTENT_STRATEGY),
                task("b", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "r")));
        assertThatThrownBy(() -> validator.validate(researcher, Set.of()))
                .hasMessageContaining("output");

        var creator = new PlanSpec(1, List.of(
                task("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("c", AgentRole.CREATOR, ArtifactType.REVIEW_REPORT, "s")));
        assertThatThrownBy(() -> validator.validate(creator, Set.of()))
                .hasMessageContaining("output");
    }

    @Test
    void rejectsMissingCreatorAndMissingStrategyAndReviewerWithoutDraft() {
        var noCreator = new PlanSpec(1, List.of(
                task("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY)));
        assertThatThrownBy(() -> validator.validate(noCreator, Set.of()))
                .hasMessageContaining("CREATOR");

        var creatorWithoutStrategy = new PlanSpec(1, List.of(
                task("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT)));
        assertThatThrownBy(() -> validator.validate(creatorWithoutStrategy, Set.of()))
                .hasMessageContaining("CONTENT_STRATEGY");

        var reviewerWithoutDraft = new PlanSpec(1, List.of(
                task("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "s"),
                task("r", AgentRole.REVIEWER, ArtifactType.REVIEW_REPORT, "s")));
        assertThatThrownBy(() -> validator.validate(reviewerWithoutDraft, Set.of()))
                .hasMessageContaining("CONTENT_DRAFT");
    }

    @Test
    void rejectsUnreachableRequiredArtifact() {
        var plan = new PlanSpec(1, List.of(
                task("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                taskWithRequired("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT,
                        Set.of(ArtifactType.EVIDENCE_PACK), "s")));
        assertThatThrownBy(() -> validator.validate(plan, Set.of()))
                .hasMessageContaining("required");
    }

    @Test
    void taskCannotSatisfyARequirementWithItsOwnOutput() {
        var plan = new PlanSpec(1, List.of(
                task("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                taskWithRequired("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT,
                        Set.of(ArtifactType.CONTENT_DRAFT), "s")));

        assertThatThrownBy(() -> validator.validate(plan, Set.of()))
                .hasMessageContaining("required");
    }

    @Test
    void initialArtifactsCanSatisfyRequiredTypes() {
        var plan = new PlanSpec(1, List.of(
                taskWithRequired("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY,
                        Set.of(ArtifactType.EVIDENCE_PACK)),
                task("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "s")));
        var stages = validator.validate(plan, Set.of(ArtifactType.EVIDENCE_PACK));
        assertThat(stages).hasSize(2);
    }

    @Test
    void rejectsSchemaVersionAndTaskCount() {
        assertThatThrownBy(() -> validator.validate(new PlanSpec(2, List.of(
                task("s", AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY),
                task("c", AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT, "s"))), Set.of()))
                .hasMessageContaining("schemaVersion");

        var thirteen = new java.util.ArrayList<PlanTask>();
        for (int i = 0; i < 13; i++) {
            thirteen.add(new PlanTask("t" + i, AgentRole.STRATEGIST, "o", List.of(),
                    Set.of(), ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY));
        }
        assertThatThrownBy(() -> validator.validate(new PlanSpec(1, thirteen), Set.of()))
                .hasMessageContaining("tasks");
    }
}
