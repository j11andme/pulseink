package com.pulseink.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.model.JacksonPlanParser;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunCoordinatorTest {

    private static final String HIGH_RISK_PLAN = """
            {"schemaVersion":1,"tasks":[
              {"taskId":"research-1","role":"RESEARCHER","objective":"research a","dependsOn":[],
               "requiredArtifactTypes":[],"outputArtifactType":"EVIDENCE_PACK","access":"READ_ONLY"},
              {"taskId":"research-2","role":"RESEARCHER","objective":"research b","dependsOn":[],
               "requiredArtifactTypes":[],"outputArtifactType":"EVIDENCE_PACK","access":"READ_ONLY"},
              {"taskId":"strategy","role":"STRATEGIST","objective":"strategy","dependsOn":["research-1","research-2"],
               "requiredArtifactTypes":["EVIDENCE_PACK"],"outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
              {"taskId":"create-blog","role":"CREATOR","objective":"blog","dependsOn":["strategy"],
               "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"},
              {"taskId":"create-social","role":"CREATOR","objective":"social","dependsOn":["strategy"],
               "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"},
              {"taskId":"review","role":"REVIEWER","objective":"review","dependsOn":["create-blog","create-social"],
               "requiredArtifactTypes":[],"outputArtifactType":"REVIEW_REPORT","access":"READ_ONLY"}]}
            """;

    private static final String FOUR_RESEARCH_PLAN = """
            {"schemaVersion":1,"tasks":[
              {"taskId":"research-1","role":"RESEARCHER","objective":"a","dependsOn":[],"requiredArtifactTypes":[],"outputArtifactType":"EVIDENCE_PACK","access":"READ_ONLY"},
              {"taskId":"research-2","role":"RESEARCHER","objective":"b","dependsOn":[],"requiredArtifactTypes":[],"outputArtifactType":"EVIDENCE_PACK","access":"READ_ONLY"},
              {"taskId":"research-3","role":"RESEARCHER","objective":"c","dependsOn":[],"requiredArtifactTypes":[],"outputArtifactType":"EVIDENCE_PACK","access":"READ_ONLY"},
              {"taskId":"research-4","role":"RESEARCHER","objective":"d","dependsOn":[],"requiredArtifactTypes":[],"outputArtifactType":"EVIDENCE_PACK","access":"READ_ONLY"},
              {"taskId":"strategy","role":"STRATEGIST","objective":"strategy","dependsOn":["research-1","research-2","research-3","research-4"],"requiredArtifactTypes":["EVIDENCE_PACK"],"outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
              {"taskId":"create","role":"CREATOR","objective":"draft","dependsOn":["strategy"],"requiredArtifactTypes":[],"outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"}]}
            """;

    private RunCoordinator coordinator(
            com.pulseink.agent.model.AgentModelPort port, ToolRegistry registry) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), port);
        var router = new ModelRouter(List.of(route));
        var reactLoop = new ReactLoop(router, new JacksonAgentDecisionParser(), registry,
                new BudgetTracker.MutableClock(Instant.now()));
        var runner = new RoleAgentRunner(router, new JacksonPlanParser(),
                new PlanValidator(12), reactLoop);
        var profileFactory = new RoleProfileFactory(
                new com.pulseink.client.profile.YamlRoleProfileCatalog("agent-profiles"));
        return new RunCoordinator(
                runner,
                new JacksonPlanParser(),
                new PlanValidator(12),
                Executors.newVirtualThreadPerTaskExecutor(),
                3,
                12000,
                profileFactory,
                3);
    }

    private static List<String> startedOrder(List<AgentRuntimeEvent> events) {
        return events.stream()
                .filter(AgentRuntimeEvent.TaskStarted.class::isInstance)
                .map(e -> ((AgentRuntimeEvent.TaskStarted) e).taskId())
                .toList();
    }

    private AgentExecutionRequest request() {
        return request(1L);
    }

    private AgentExecutionRequest request(long runId) {
        return new AgentExecutionRequest(
                runId, "run-" + runId, ExecutionMode.ORCHESTRATED,
                profile(), "brief", List.of(),
                BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    private AgentExecutionRequest requestWithPrior(
            com.pulseink.agent.artifact.AgentArtifact... prior) {
        return new AgentExecutionRequest(
                1L, "run-1", ExecutionMode.ORCHESTRATED, profile(), "brief",
                List.of(prior), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    private AgentProfile profile() {
        var deadline = Instant.now().plus(Duration.ofMinutes(30));
        return AgentProfile.role("unified", com.pulseink.agent.orchestration.AgentRole.CREATOR,
                Set.of(), new ModelPolicy(List.of("fake"), Set.of()),
                new ExecutionBudget(100, 100, 1_000_000L, 100, 1, deadline),
                "You are PulseInk.", Set.of(), 20, 20, 10);
    }

    @Test
    void supportedModeIsOrchestrated() {
        var coordinator = coordinator(emptyPort(), registryWithKnowledgeSearch());
        assertThat(coordinator.supportedMode()).isEqualTo(ExecutionMode.ORCHESTRATED);
    }

    @Test
    void highRiskMultiChannelRunExecutesAllFiveRoles() {
        var coordinator = coordinator(roleModelPort(Map.of()), registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(request(), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        var order = startedOrder(events);
        assertThat(order).contains("planner", "research-1", "research-2", "strategy",
                "create-blog", "create-social", "review");
        assertThat(result.artifacts()).extracting(a -> a.type())
                .contains(ArtifactType.PLAN, ArtifactType.EVIDENCE_PACK,
                        ArtifactType.CONTENT_STRATEGY, ArtifactType.CONTENT_DRAFT,
                        ArtifactType.REVIEW_REPORT);
        assertThat(events).anyMatch(AgentRuntimeEvent.TaskCompleted.class::isInstance);
        assertThat(result.finalBudget().modelCallsUsed())
                .isEqualTo(result.metrics().modelCalls());
        assertThat(events.stream()
                .filter(AgentRuntimeEvent.ArtifactCompleted.class::isInstance))
                .hasSize(result.artifacts().size());
    }

    @Test
    void coordinatorCanServeMultipleRunsWithoutSharingBudgetState() {
        var coordinator = coordinator(
                new com.pulseink.client.model.FakeModelAdapter(),
                registryWithKnowledgeSearch());

        var first = coordinator.execute(request(1L), event -> {});
        var second = coordinator.execute(request(2L), event -> {});

        assertThat(first.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(second.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(second.finalBudget()).isEqualTo(first.finalBudget());
    }

    @Test
    void stageNeverRunsMoreThanThreeTasksConcurrently() throws Exception {
        var firstThreeEntered = new CountDownLatch(3);
        var fourthEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        var callsByTask = new ConcurrentHashMap<String, AtomicInteger>();
        com.pulseink.agent.model.AgentModelPort port = (modelRequest, consumer) -> {
            String taskId = taskIdOf(modelRequest.requestId());
            String content;
            if (!modelRequest.requestId().contains("-task-")) {
                content = FOUR_RESEARCH_PLAN;
            } else if (taskId.startsWith("research-")) {
                int call = callsByTask.computeIfAbsent(taskId, ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (call == 1) {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    if (now == 4) {
                        fourthEntered.countDown();
                    }
                    firstThreeEntered.countDown();
                    try {
                        firstThreeEntered.await();
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                    content = toolCallJson(taskId);
                } else {
                    content = researchJson(taskId);
                }
            } else if (taskId.equals("strategy")) {
                content = strategyJson();
            } else {
                content = creatorJson(taskId);
            }
            emit(modelRequest, consumer, content);
            return () -> {};
        };
        var coordinator = coordinator(port, registryWithKnowledgeSearch());
        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var resultFuture = caller.submit(() -> coordinator.execute(request(), event -> {}));
            try {
                assertThat(firstThreeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(fourthEntered.await(250, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(resultFuture.get(10, TimeUnit.SECONDS).terminalReason())
                    .isEqualTo(AgentTerminalReason.SUCCEEDED);
        }
        assertThat(maxActive.get()).isLessThanOrEqualTo(3);
    }

    @Test
    void independentResearchersRunOverlapping() throws Exception {
        var barrier = new CountDownLatch(2);
        var entered = new AtomicInteger();
        var researchCalls = new ConcurrentHashMap<String, AtomicInteger>();
        com.pulseink.agent.model.AgentModelPort port = (request, consumer) -> {
            String taskId = taskIdOf(request.requestId());
            String content;
            if ("planner".equals(taskId) || !request.requestId().contains("-task-")) {
                content = HIGH_RISK_PLAN;
            } else if (taskId.startsWith("research-")) {
                int n = researchCalls.computeIfAbsent(taskId, k -> new AtomicInteger())
                        .incrementAndGet();
                if (n == 1) {
                    content = toolCallJson(taskId);
                } else {
                    content = researchJson(taskId);
                    entered.incrementAndGet();
                    barrier.countDown();
                    try {
                        if (!barrier.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("research tasks did not overlap");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else if ("strategy".equals(taskId)) {
                content = strategyJson();
            } else if (taskId.startsWith("create-")) {
                content = creatorJson(taskId);
            } else {
                content = reviewJson();
            }
            emit(request, consumer, content);
            return () -> {};
        };
        var coordinator = coordinator(port, registryWithKnowledgeSearch());

        var result = coordinator.execute(request(), event -> {});

        assertThat(entered.get()).isEqualTo(2);
        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
    }

    @Test
    void strategistWaitsForResearchersAndCreatorsWaitForStrategy() {
        var coordinator = coordinator(roleModelPort(Map.of()), registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        coordinator.execute(request(), events::add);

        var order = startedOrder(events);
        assertThat(order.indexOf("research-1")).isLessThan(order.indexOf("strategy"));
        assertThat(order.indexOf("research-2")).isLessThan(order.indexOf("strategy"));
        assertThat(order.indexOf("strategy")).isLessThan(order.indexOf("create-blog"));
        assertThat(order.indexOf("strategy")).isLessThan(order.indexOf("create-social"));
        assertThat(order.indexOf("create-blog")).isLessThan(order.indexOf("review"));
    }

    @Test
    void parallelCreatorsProduceStableResultOrder() {
        var coordinator = coordinator(roleModelPort(Map.of()), registryWithKnowledgeSearch());

        var result = coordinator.execute(request(), event -> {});

        var drafts = result.artifacts().stream()
                .filter(a -> a.type() == ArtifactType.CONTENT_DRAFT)
                .map(a -> a.taskId())
                .toList();
        assertThat(drafts).containsExactly("create-blog", "create-social");
    }

    @Test
    void taskFailureStopsDownstreamTasks() {
        var coordinator = coordinator(
                roleModelPort(Map.of("research-2", "MODEL_FAILURE")),
                registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(request(), events::add);

        assertThat(result.terminalReason()).isIn(
                AgentTerminalReason.MODEL_FAILURE,
                AgentTerminalReason.INVALID_MODEL_OUTPUT);
        var order = startedOrder(events);
        assertThat(order).contains("research-2");
        assertThat(order).doesNotContain("strategy");
        assertThat(result.artifacts()).anyMatch(a -> a.type() == ArtifactType.PLAN);
    }

    @Test
    void priorPlanSkipsPlannerAndPriorTasks() {
        var planArtifact = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-plan-v1", 1L, "planner", ArtifactType.PLAN, 1,
                Map.of("plan", HIGH_RISK_PLAN), List.of(), Instant.now());
        var coordinator = coordinator(roleModelPort(Map.of()), registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(requestWithPrior(planArtifact), events::add);

        var order = startedOrder(events);
        assertThat(order).doesNotContain("planner");
        assertThat(order).contains("research-1", "strategy", "review");
        assertThat(result.artifacts()).hasSize(7);
    }

    @Test
    void restoredTaskArtifactsAreNotReExecuted() {
        var planArtifact = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-plan-v1", 1L, "planner", ArtifactType.PLAN, 1,
                Map.of("plan", HIGH_RISK_PLAN), List.of(), Instant.now());
        var doneResearch = com.pulseink.agent.artifact.AgentArtifact.create(
                "r1", 1L, "research-1", ArtifactType.EVIDENCE_PACK, 1,
                Map.of("e", 1), List.of("ref-1"), Instant.now());
        var coordinator = coordinator(roleModelPort(Map.of()), registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        coordinator.execute(requestWithPrior(planArtifact, doneResearch), events::add);

        var order = startedOrder(events);
        assertThat(order).doesNotContain("research-1");
        assertThat(order).contains("research-2", "strategy");
    }

    @Test
    void restoredTaskWithoutItsDependenciesFailsClosed() {
        var planArtifact = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-plan-v1", 1L, "planner", ArtifactType.PLAN, 1,
                Map.of("plan", HIGH_RISK_PLAN), List.of(), Instant.now());
        var orphanDraft = com.pulseink.agent.artifact.AgentArtifact.create(
                "draft", 1L, "create-blog", ArtifactType.CONTENT_DRAFT, 1,
                Map.of("draft", "orphan"), List.of(), Instant.now());
        var coordinator = coordinator(roleModelPort(Map.of()), registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(
                requestWithPrior(planArtifact, orphanDraft), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.CHECKPOINT_INVALID);
        assertThat(startedOrder(events)).isEmpty();
    }

    @Test
    void corruptPlanFailsClosedWithoutExecutingTasks() {
        var planArtifact = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-plan-v1", 1L, "planner", ArtifactType.PLAN, 1,
                Map.of("plan", "{not a plan"), List.of(), Instant.now());
        var coordinator = coordinator(emptyPort(), registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(requestWithPrior(planArtifact), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.CHECKPOINT_INVALID);
        assertThat(startedOrder(events)).isEmpty();
    }

    @Test
    void failedStyleReviewRepairsOnlyAffectedCreatorAndReviewer() {
        var coordinator = coordinator(roleModelPortWithReviews(List.of(
                failedReview("STYLE", List.of("create-blog")), reviewJson())),
                registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(request(), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(startedOrder(events)).filteredOn("research-1"::equals).hasSize(1);
        assertThat(startedOrder(events)).filteredOn("research-2"::equals).hasSize(1);
        assertThat(startedOrder(events)).filteredOn("strategy"::equals).hasSize(1);
        assertThat(startedOrder(events)).filteredOn("create-blog"::equals).hasSize(2);
        assertThat(startedOrder(events)).filteredOn("create-social"::equals).hasSize(1);
        assertThat(startedOrder(events)).filteredOn("review"::equals).hasSize(2);
        assertThat(result.artifacts().stream()
                .filter(a -> a.taskId().equals("create-blog")))
                .extracting(a -> a.artifactVersion() + ":" + a.status())
                .containsExactly("1:INVALIDATED", "2:VALID");
        assertThat(events).anyMatch(AgentRuntimeEvent.RepairRoundStarted.class::isInstance);
        assertThat(events).anyMatch(AgentRuntimeEvent.ArtifactInvalidated.class::isInstance);
    }

    @Test
    void thirdFailedReviewStopsAfterTwoAutomaticRepairRounds() {
        var coordinator = coordinator(roleModelPortWithReviews(List.of(
                failedReview("STYLE", List.of("create-blog")),
                failedReview("STYLE", List.of("create-blog")),
                failedReview("STYLE", List.of("create-blog")))),
                registryWithKnowledgeSearch());
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = coordinator.execute(request(), events::add);

        assertThat(result.terminalReason())
                .isEqualTo(AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED);
        assertThat(startedOrder(events)).filteredOn("create-blog"::equals).hasSize(3);
        assertThat(startedOrder(events)).filteredOn("review"::equals).hasSize(3);
        assertThat(events).anyMatch(AgentRuntimeEvent.RepairExhausted.class::isInstance);
    }

    @Test
    void planGapInvalidatesOldPlanAndCreatesVersionTwo() {
        var coordinator = coordinator(roleModelPortWithReviews(List.of(
                failedReview("PLAN_GAP", List.of()), reviewJson())),
                registryWithKnowledgeSearch());

        var result = coordinator.execute(request(), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts().stream().filter(a -> a.type() == ArtifactType.PLAN))
                .extracting(a -> a.artifactVersion() + ":" + a.status())
                .containsExactly("1:INVALIDATED", "2:VALID");
    }

    // ---- helpers ----

    private static ToolRegistry registryWithKnowledgeSearch() {
        JavaToolProvider.JavaToolHandler handler = (call, timeout) -> ToolResult.of(
                "{\"valid\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("sourceRefs", "ref-research-1,ref-research-2,"
                        + "ref-research-3,ref-research-4"));
        var schema = ToolDefinition.Schema.of(
                Map.of("query", ToolDefinition.PropertySpec.of("string")),
                Set.of("query"), false);
        return new ToolRegistry(List.of(new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "knowledge_search", "search",
                                schema, ToolRisk.READ),
                        handler)))));
    }

    private static String taskIdOf(String requestId) {
        int marker = requestId.lastIndexOf("-task-");
        return marker >= 0 ? requestId.substring(marker + 6) : requestId;
    }

    /**
     * Deterministic per-request scripted model: research tasks answer TOOL_CALL then FINAL;
     * other roles answer FINAL directly. Failures map taskId to "MODEL_FAILURE".
     */
    private static com.pulseink.agent.model.AgentModelPort roleModelPort(
            Map<String, String> failures) {
        var callsByTask = new ConcurrentHashMap<String, AtomicInteger>();
        return (request, consumer) -> {
            String taskId = taskIdOf(request.requestId());
            String content;
            if ("planner".equals(taskId) || !request.requestId().contains("-task-")) {
                content = HIGH_RISK_PLAN;
            } else if (failures.containsKey(taskId)) {
                consumer.accept(new com.pulseink.agent.model.ModelStreamEvent.Started(
                        request.requestId(), "fake", "m"));
                consumer.accept(new com.pulseink.agent.model.ModelStreamEvent.Failed(
                        request.requestId(), "MODEL_PROVIDER_ERROR", "provider exploded"));
                return () -> {};
            } else {
                int n = callsByTask.computeIfAbsent(taskId, k -> new AtomicInteger())
                        .incrementAndGet();
                if (taskId.startsWith("research-")) {
                    content = n == 1 ? toolCallJson(taskId) : researchJson(taskId);
                } else if ("strategy".equals(taskId)) {
                    content = strategyJson();
                } else if (taskId.startsWith("create-")) {
                    content = creatorJson(taskId);
                } else {
                    content = reviewJson();
                }
            }
            emit(request, consumer, content);
            return () -> {};
        };
    }

    private static com.pulseink.agent.model.AgentModelPort roleModelPortWithReviews(
            List<String> reviews) {
        var callsByTask = new ConcurrentHashMap<String, AtomicInteger>();
        var reviewIndex = new AtomicInteger();
        return (request, consumer) -> {
            String taskId = taskIdOf(request.requestId());
            String content;
            if (!request.requestId().contains("-task-")) {
                content = HIGH_RISK_PLAN;
            } else if (taskId.startsWith("research-")) {
                int n = callsByTask.computeIfAbsent(taskId, ignored -> new AtomicInteger())
                        .incrementAndGet();
                content = n == 1 ? toolCallJson(taskId) : researchJson(taskId);
            } else if (taskId.equals("strategy")) {
                content = strategyJson();
            } else if (taskId.startsWith("create-")) {
                content = creatorJson(taskId);
            } else {
                int index = reviewIndex.getAndIncrement();
                content = reviews.get(Math.min(index, reviews.size() - 1));
            }
            emit(request, consumer, content);
            return () -> {};
        };
    }

    private static com.pulseink.agent.model.AgentModelPort emptyPort() {
        return (request, consumer) -> () -> {};
    }

    private static void emit(com.pulseink.agent.model.ModelRequest request,
                             java.util.function.Consumer<com.pulseink.agent.model.ModelStreamEvent> consumer,
                             String content) {
        consumer.accept(new com.pulseink.agent.model.ModelStreamEvent.Started(
                request.requestId(), "fake", "m"));
        consumer.accept(new com.pulseink.agent.model.ModelStreamEvent.ContentDelta(
                request.requestId(), content));
        consumer.accept(new com.pulseink.agent.model.ModelStreamEvent.Completed(
                request.requestId(), "STOP"));
    }

    private static String toolCallJson(String taskId) {
        return "{\"decision\":\"TOOL_CALL\",\"decisionSummary\":\"search\","
                + "\"toolCall\":{\"qualifiedName\":\"builtin.knowledge_search\","
                + "\"arguments\":{\"query\":\"" + taskId + "\"}}}";
    }

    private static String researchJson(String taskId) {
        return "{\"decision\":\"FINAL\",\"decisionSummary\":\"evidence-" + taskId + "\","
                + "\"artifacts\":[{\"type\":\"EVIDENCE_PACK\","
                + "\"content\":{\"task\":\"" + taskId + "\"},\"sourceRefs\":[\"ref-"
                + taskId + "\"]}]}";
    }

    private static String strategyJson() {
        return "{\"decision\":\"FINAL\",\"decisionSummary\":\"strategy\","
                + "\"artifacts\":[{\"type\":\"CONTENT_STRATEGY\","
                + "\"content\":{\"s\":1},\"sourceRefs\":[]}]}";
    }

    private static String creatorJson(String taskId) {
        return "{\"decision\":\"FINAL\",\"decisionSummary\":\"draft-" + taskId + "\","
                + "\"artifacts\":[{\"type\":\"CONTENT_DRAFT\","
                + "\"content\":{\"task\":\"" + taskId + "\"},\"sourceRefs\":[]}]}";
    }

    private static String reviewJson() {
        return "{\"decision\":\"FINAL\",\"decisionSummary\":\"review\","
                + "\"artifacts\":[{\"type\":\"REVIEW_REPORT\","
                + "\"content\":{\"passed\":true,\"issues\":[]},\"sourceRefs\":[]}]}";
    }

    private static String failedReview(String type, List<String> affectedTaskIds) {
        String affected = affectedTaskIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"decision\":\"FINAL\",\"decisionSummary\":\"review failed\","
                + "\"artifacts\":[{\"type\":\"REVIEW_REPORT\",\"content\":{"
                + "\"passed\":false,\"issues\":[{\"type\":\"" + type
                + "\",\"affectedTaskIds\":[" + affected
                + "],\"message\":\"repair\"}]},\"sourceRefs\":[]}]}";
    }
}
