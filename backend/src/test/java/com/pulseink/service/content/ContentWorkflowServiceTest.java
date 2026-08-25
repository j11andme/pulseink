package com.pulseink.service.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.repair.StrictReviewArtifactInterpreter;
import com.pulseink.client.model.JacksonPlanParser;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.content.ApprovalRecord;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentOrigin;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewReport;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.service.campaign.RunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ContentWorkflowServiceTest {

    private final FakeContentRepository contents = new FakeContentRepository();
    private final FakeRunRepository runs = new FakeRunRepository();
    private final ContentWorkflowService service = new ContentWorkflowService(
            contents, runs, new StrictReviewArtifactInterpreter(),
            new JacksonPlanParser(), immediateTransactions());

    @Test
    void capturePersistsCompleteDraftAndReviewHistoryButSkipsFailedRun() {
        var plan = plan();
        var invalidDraft = AgentArtifact.create("draft-v1", 1L, "create-blog",
                ArtifactType.CONTENT_DRAFT, 1, Map.of("body", "old"), List.of(),
                Instant.now()).withStatus(ArtifactStatus.INVALIDATED);
        var validDraft = AgentArtifact.create("draft-v2", 1L, "create-blog",
                ArtifactType.CONTENT_DRAFT, 2, Map.of("body", "new"), List.of(),
                Instant.now());
        var review = AgentArtifact.create("review-v2", 1L, "review",
                ArtifactType.REVIEW_REPORT, 2,
                Map.of("passed", true, "issues", List.of()), List.of(), Instant.now());

        service.capture(1L, result(AgentTerminalReason.SUCCEEDED,
                List.of(plan, invalidDraft, validDraft, review)));
        service.capture(1L, result(AgentTerminalReason.SUCCEEDED,
                List.of(plan, invalidDraft, validDraft, review)));
        service.capture(1L, result(AgentTerminalReason.MODEL_FAILURE,
                List.of(validDraft)));

        assertThat(contents.draftArtifactIds)
                .containsExactly("draft-v1", "draft-v2", "draft-v1", "draft-v2");
        assertThat(contents.reviewArtifactIds)
                .containsExactly("review-v2", "review-v2");
        assertThat(contents.assessments).allSatisfy(assessment ->
                assertThat(assessment.passed()).isTrue());
    }

    @Test
    void humanInterventionResultIsCaptured() {
        var draft = AgentArtifact.create("draft-v1", 1L, "create-blog",
                ArtifactType.CONTENT_DRAFT, 1, Map.of("body", "draft"), List.of(),
                Instant.now());

        service.capture(1L, result(AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED,
                List.of(draft)));

        assertThat(contents.draftArtifactIds).containsExactly("draft-v1");
    }

    @Test
    void humanEditResumesWaitingHumanRunAndStableConflictIsPropagated() {
        var run = runs.add(run(RunState.WAITING_HUMAN));
        contents.item = item(run.id(), 1, 4L);

        var version = service.createVersion(new CreateContentVersionUseCase.Command(
                10L, 1, 4L, Map.of("body", "human"), List.of("source"), 7L));

        assertThat(version.origin()).isEqualTo(ContentOrigin.HUMAN);
        assertThat(run.state()).isEqualTo(RunState.WAITING_APPROVAL);

        contents.conflictOnAppend = true;
        assertThatThrownBy(() -> service.createVersion(
                new CreateContentVersionUseCase.Command(
                        10L, 2, 5L, Map.of("body", "stale"), List.of(), 7L)))
                .isInstanceOf(ContentWorkflowException.class)
                .extracting("code")
                .isEqualTo(ContentErrorCode.CONTENT_VERSION_CONFLICT);
    }

    private AgentArtifact plan() {
        String json = """
                {"schemaVersion":1,"tasks":[
                  {"taskId":"create-blog","role":"CREATOR","objective":"create",
                   "dependsOn":[],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"},
                  {"taskId":"review","role":"REVIEWER","objective":"review",
                   "dependsOn":["create-blog"],"requiredArtifactTypes":["CONTENT_DRAFT"],
                   "outputArtifactType":"REVIEW_REPORT","access":"READ_ONLY"}]}
                """;
        return AgentArtifact.create("plan-v1", 1L, "planner", ArtifactType.PLAN,
                1, Map.of("plan", json), List.of(), Instant.now());
    }

    private AgentExecutionResult result(AgentTerminalReason reason,
                                        List<AgentArtifact> artifacts) {
        return new AgentExecutionResult(1L, ExecutionMode.ORCHESTRATED, artifacts,
                BudgetSnapshot.ZERO, reason,
                new AgentExecutionResult.Metrics(0, 0, 0, 0));
    }

    private static CampaignRun run(RunState state) {
        return CampaignRun.materialize(1L, 1L, ExecutionPolicy.ORCHESTRATED,
                state, ExecutionMode.ORCHESTRATED, "selector-v1", List.of(), Map.of(),
                8_000L, null, 0L, Instant.now(), null, Instant.now(), Instant.now());
    }

    private static ContentItem item(long runId, int current, long version) {
        var v1 = new ContentVersion(20L, 10L, current, Map.of("body", "agent"),
                List.of(), ContentOrigin.AGENT, "draft-v1", current,
                ArtifactStatus.VALID, null, Instant.now());
        return new ContentItem(10L, runId, "create-blog", current, version,
                Instant.now(), Instant.now(), List.of(v1), List.of());
    }

    @SuppressWarnings("unchecked")
    private static TransactionTemplate immediateTransactions() {
        var template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0))
                        .doInTransaction(null));
        return template;
    }

    private static final class FakeRunRepository implements RunRepository {
        private CampaignRun item;

        CampaignRun add(CampaignRun run) {
            this.item = run;
            return run;
        }

        @Override public CampaignRun insert(CampaignRun run) { throw new UnsupportedOperationException(); }
        @Override public Optional<CampaignRun> findById(long runId) { return Optional.ofNullable(item); }
        @Override public void update(CampaignRun run) { this.item = run; }
        @Override public List<CampaignRun> findByCampaignId(long campaignId) { return List.of(); }
    }

    private static final class FakeContentRepository implements ContentWorkflowRepository {
        final List<String> draftArtifactIds = new ArrayList<>();
        final List<String> reviewArtifactIds = new ArrayList<>();
        final List<ReviewAssessment> assessments = new ArrayList<>();
        ContentItem item;
        boolean conflictOnAppend;

        @Override
        public void captureAgentVersion(long runId, String taskId, AgentArtifact artifact) {
            draftArtifactIds.add(artifact.artifactId());
        }

        @Override
        public void captureReview(long runId, AgentArtifact artifact,
                                  ReviewAssessment assessment, int repairRound) {
            reviewArtifactIds.add(artifact.artifactId());
            assessments.add(assessment);
        }

        @Override public List<ContentItem> findByRunId(long runId) {
            return item == null ? List.of() : List.of(item);
        }
        @Override public Optional<ContentItem> findById(long contentId) {
            return Optional.ofNullable(item);
        }

        @Override
        public ContentVersion appendHumanVersion(long contentId, int expectedCurrentVersionNo,
                                                 long expectedItemVersion,
                                                 Map<String, Object> content,
                                                 List<String> sourceRefs, long actorId) {
            if (conflictOnAppend) {
                throw new IllegalStateException("stale");
            }
            return new ContentVersion(21L, contentId, expectedCurrentVersionNo + 1,
                    content, sourceRefs, ContentOrigin.HUMAN, null, null,
                    null, actorId, Instant.now());
        }

        @Override
        public ApprovalRecord approve(long contentId, long contentVersionId,
                                      int expectedCurrentVersionNo, long expectedItemVersion,
                                      String comment, long actorId) {
            return new ApprovalRecord(1L, contentVersionId, actorId, comment, Instant.now());
        }

        @Override public List<ReviewReport> findReviewsByRunId(long runId) { return List.of(); }
    }
}
