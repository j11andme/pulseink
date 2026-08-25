package com.pulseink.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.api.ExecutionOwnershipGuard;
import com.pulseink.agent.api.ExecutionOwnershipLostException;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamHandle;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.config.properties.RunLeaseProperties;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.repository.cache.RedisRunLeaseAdapter;
import com.pulseink.support.MemoryTestContainers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Lease integration over the real RunExecutionService: a second owner is skipped without any
 * state change, a Redis cache outage never fails a run, and a lost lease flips the guard and
 * propagates out of the engines instead of being swallowed as an ordinary failure.
 */
@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=false",
        "pulseink.memory.index-worker-enabled=false",
        "pulseink.run-lease.enabled=true",
        "pulseink.run-lease.ttl=2s",
        "pulseink.run-lease.renew-interval=200ms",
        "spring.main.allow-bean-definition-overriding=true"
})
class LeasedRunExecutionIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
        registry.add("spring.data.redis.url", MemoryTestContainers::redisUrl);
    }

    @Autowired RunExecutionUseCase execution;
    @Autowired RunLeasePort leasePort;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheOutage cacheOutage;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"campaign_insight", "content_metric_daily",
                "feedback_inbox", "publication", "approval_record", "content_version",
                "content_item", "run_checkpoint", "run_event", "campaign_run", "campaign",
                "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (1,'editor','x','EDITOR',TRUE)");
        jdbc.update("""
                INSERT INTO campaign(id,name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES (1,'c','o','a','[\"BLOG\"]','[]','DRAFT',1,0)
                """);
        jdbc.update("""
                INSERT INTO campaign_run(id,campaign_id,requested_policy,selected_mode,
                                         selector_policy_version,state,version)
                VALUES (2,1,'DIRECT','DIRECT','selector-v1','CREATED',0)
                """);
        cacheOutage.enabled = false;
    }

    @Test
    void secondOwnerIsSkippedWithoutAnyStateChange() {
        leasePort.tryAcquire(2L, "external-owner", Duration.ofSeconds(10));

        assertThat(execution.execute(2L)).isNull();

        assertThat(runState(2L)).isEqualTo("CREATED");
        assertThat(countEvents(2L)).isZero();
    }

    @Test
    void cacheOutageNeverFailsTheRun() {
        cacheOutage.enabled = true;

        var result = execution.execute(2L);

        assertThat(result.terminalReason())
                .isEqualTo(com.pulseink.agent.api.AgentTerminalReason.SUCCEEDED);
        assertThat(runState(2L)).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void renewalFailureFlipsTheGuardAndAllowsTakeover() {
        var renewer = Executors.newSingleThreadScheduledExecutor();
        try {
            var manager = new RunLeaseManager(
                    new RedisRunLeaseAdapter(redisTemplate),
                    new RunLeaseProperties(true, null, Duration.ofSeconds(1),
                            Duration.ofMillis(200)),
                    "test-owner-" + UUID.randomUUID().toString().substring(0, 8),
                    renewer);
            var handle = manager.tryAcquire(202L);
            assertThat(handle.owned()).isTrue();

            // Simulate TTL expiry / takeover: the key disappears while we still hold it.
            redisTemplate.delete("pulseink:run:202:lease");

            awaitUntil(() -> {
                try {
                    handle.guard().assertCanProceed();
                    return false;
                } catch (ExecutionOwnershipLostException lost) {
                    return true;
                }
            });

            assertThat(handle.owned()).isFalse();
            assertThat(leasePort.tryAcquire(202L, "next-owner", Duration.ofSeconds(5)))
                    .isPresent();
            handle.close();
        } finally {
            renewer.shutdownNow();
        }
    }

    @Test
    void ownershipLossPropagatesThroughReactLoopWithoutModelCalls() {
        var calls = new AtomicInteger();
        var recording = new RecordingPort(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"draft ready",
                         "artifacts":[{"type":"CONTENT_DRAFT","content":{"text":"PulseInk"}}]}
                        """))), calls);
        var router = new ModelRouter(List.of(
                new ModelRoute("fake", "pulseink-fake", Set.of(), recording)));
        var loop = new ReactLoop(router, new JacksonAgentDecisionParser(),
                new ToolRegistry(List.of()));
        var profile = com.pulseink.agent.orchestration.AgentProfile.unified(
                "unified", Set.of(),
                new com.pulseink.agent.model.ModelPolicy(List.of("fake"), Set.of()),
                com.pulseink.agent.budget.ExecutionBudget.defaultReact(
                        Instant.now().plus(Duration.ofMinutes(30))));
        var request = new com.pulseink.agent.api.AgentExecutionRequest(
                2L, "request-1", com.pulseink.domain.execution.ExecutionMode.REACT,
                profile, "objective", List.of(),
                com.pulseink.agent.budget.BudgetSnapshot.ZERO,
                com.pulseink.agent.tool.ApprovalState.NOT_REQUIRED,
                com.pulseink.agent.artifact.AgentArtifact.UNIFIED_TASK_ID,
                List.of(CampaignChannel.BLOG),
                (ExecutionOwnershipGuard) () -> {
                    throw new ExecutionOwnershipLostException(2L);
                });

        assertThatThrownBy(() -> loop.execute(request, event -> {}))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void ownershipLossPropagatesThroughCoordinatorInsteadOfRuntimeFailed() {
        var calls = new AtomicInteger();
        var recording = new RecordingPort(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(plannerJson()))), calls);
        var router = new ModelRouter(List.of(
                new ModelRoute("fake", "pulseink-fake", Set.of(), recording)));
        var parser = new JacksonAgentDecisionParser();
        var reactLoop = new ReactLoop(router, parser, new ToolRegistry(List.of()));
        var runner = new com.pulseink.agent.orchestration.RoleAgentRunner(
                router, new com.pulseink.client.model.JacksonPlanParser(),
                new com.pulseink.agent.plan.PlanValidator(12), reactLoop);
        var profileFactory = new com.pulseink.agent.orchestration.RoleProfileFactory(
                new com.pulseink.client.profile.YamlRoleProfileCatalog("agent-profiles"));
        var coordinator = new com.pulseink.agent.orchestration.RunCoordinator(
                runner,
                new com.pulseink.client.model.JacksonPlanParser(),
                new com.pulseink.agent.plan.PlanValidator(12),
                Executors.newVirtualThreadPerTaskExecutor(),
                3, 12_000, profileFactory, 3);
        var profile = com.pulseink.agent.orchestration.AgentProfile.unified(
                "unified", Set.of(),
                new com.pulseink.agent.model.ModelPolicy(List.of("fake"), Set.of()),
                com.pulseink.agent.budget.ExecutionBudget.defaultReact(
                        Instant.now().plus(Duration.ofMinutes(30))));
        var request = new com.pulseink.agent.api.AgentExecutionRequest(
                2L, "request-1", com.pulseink.domain.execution.ExecutionMode.ORCHESTRATED,
                profile, "objective", List.of(),
                com.pulseink.agent.budget.BudgetSnapshot.ZERO,
                com.pulseink.agent.tool.ApprovalState.NOT_REQUIRED,
                com.pulseink.agent.artifact.AgentArtifact.UNIFIED_TASK_ID,
                List.of(CampaignChannel.BLOG),
                (ExecutionOwnershipGuard) () -> {
                    throw new ExecutionOwnershipLostException(2L);
                });

        assertThatThrownBy(() -> coordinator.execute(request, event -> {}))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void closingLeaseManagerStopsItsOwnedRenewerThread() {
        var renewer = Executors.newSingleThreadScheduledExecutor();
        var manager = new RunLeaseManager(
                leasePort,
                new RunLeaseProperties(true, null, Duration.ofSeconds(2),
                        Duration.ofMillis(200)),
                "closing-owner",
                renewer);

        manager.close();

        assertThat(renewer.isShutdown()).isTrue();
    }

    private String runState(long runId) {
        return jdbc.queryForObject(
                "SELECT state FROM campaign_run WHERE id = ?", String.class, runId);
    }

    private int countEvents(long runId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_event WHERE run_id = ?", Integer.class, runId);
    }

    private static String plannerJson() {
        return """
                {"schemaVersion":1,"tasks":[
                  {"taskId":"create-main","role":"CREATOR","objective":"write draft",
                   "dependsOn":[],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"}]}
                """;
    }

    private static void awaitUntil(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition was not met within the deadline");
    }

    private static final class RecordingPort implements AgentModelPort {

        private final FakeModelAdapter delegate;
        private final AtomicInteger calls;

        RecordingPort(FakeModelAdapter delegate, AtomicInteger calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        @Override
        public ModelStreamHandle stream(ModelRequest request,
                                        Consumer<ModelStreamEvent> events) {
            calls.incrementAndGet();
            return delegate.stream(request, events);
        }
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean("primaryModelPort")
        AgentModelPort scriptedPrimaryModel() {
            return new FakeModelAdapter(List.of(
                    FakeModelAdapter.Scene.of(directDecisionJson()),
                    FakeModelAdapter.Scene.of(directDecisionJson())));
        }

        @Bean
        @org.springframework.context.annotation.Primary
        CacheOutage cacheOutage(
                @org.springframework.beans.factory.annotation.Qualifier(
                        "runWorkingMemoryCache")
                com.pulseink.service.memory.RunWorkingMemoryCache real) {
            return new CacheOutage(real);
        }
    }

    static final class CacheOutage
            implements com.pulseink.service.memory.RunWorkingMemoryCache {

        private final com.pulseink.service.memory.RunWorkingMemoryCache delegate;
        volatile boolean enabled;

        CacheOutage(com.pulseink.service.memory.RunWorkingMemoryCache delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<com.pulseink.service.memory.RunWorkingMemory> load(
                long runId) {
            if (enabled) {
                throw new IllegalStateException("simulated cache outage");
            }
            return delegate.load(runId);
        }

        @Override
        public void put(long runId, com.pulseink.service.memory.RunWorkingMemory memory) {
            if (enabled) {
                throw new IllegalStateException("simulated cache outage");
            }
            delegate.put(runId, memory);
        }

        @Override
        public void invalidate(long runId) {
            delegate.invalidate(runId);
        }
    }

    private static String directDecisionJson() {
        return """
                {"decision":"FINAL","decisionSummary":"draft ready",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"text":"PulseInk"}}]}
                """;
    }
}
