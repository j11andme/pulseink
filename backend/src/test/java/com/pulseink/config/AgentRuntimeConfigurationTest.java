package com.pulseink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.config.properties.AgentRuntimeProperties;
import com.pulseink.client.tool.DeterministicValidateTool;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.client.tool.KnowledgeSearchTool;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import java.util.Set;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues("pulseink.model.provider=fake")
                    .withBean(KnowledgeSearchTool.class,
                            () -> new KnowledgeSearchTool(mock(QueryKnowledgeUseCase.class)))
                    .withBean(com.pulseink.service.memory.MemoryPort.class,
                            () -> mock(com.pulseink.service.memory.MemoryPort.class))
                    .withUserConfiguration(AgentRuntimeConfiguration.class, ModelConfiguration.class);

    @Test
    void registersBuiltInToolsInRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var registry = context.getBean(ToolRegistry.class);
            assertThat(registry.names()).containsExactlyInAnyOrder(
                    "builtin.deterministic_validate", "builtin.knowledge_search");

            var profile = AgentProfile.of(
                    "reviewer", com.pulseink.agent.orchestration.AgentRole.REVIEWER,
                    Set.of("builtin.deterministic_validate"));
            assertThat(registry.schemasFor(profile))
                    .extracting(t -> t.qualifiedName())
                    .contains("builtin.deterministic_validate");

            var researchProfile = AgentProfile.of(
                    "researcher", com.pulseink.agent.orchestration.AgentRole.RESEARCHER,
                    Set.of("builtin.knowledge_search"));
            assertThat(registry.schemasFor(researchProfile))
                    .extracting(t -> t.qualifiedName())
                    .containsExactly("builtin.knowledge_search");
        });
    }

    @Test
    void wiresEnginesAndRouter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DeterministicValidateTool.class);
            assertThat(context).hasSingleBean(JavaToolProvider.class);
            assertThat(context).hasSingleBean(ToolRegistry.class);
            assertThat(context).hasSingleBean(ModelRouter.class);
            assertThat(context).hasSingleBean(DirectAgentEngine.class);
            assertThat(context).hasSingleBean(ReactLoop.class);
            assertThat(context).hasSingleBean(UnifiedAgentRunner.class);
            assertThat(context.getBean(DirectAgentEngine.class).supportedMode())
                    .isEqualTo(ExecutionMode.DIRECT);
            assertThat(context.getBean(UnifiedAgentRunner.class).supportedMode())
                    .isEqualTo(ExecutionMode.REACT);
        });
    }

    @Test
    void bindsAgentRuntimeCompletionBoundary() {
        contextRunner
                .withPropertyValues(
                        "pulseink.agent-runtime.max-output-tokens-per-call=8192",
                        "pulseink.agent-runtime.completion-timeout=120s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AgentRuntimeProperties.class);
                    assertThat(properties.maxOutputTokensPerCall()).isEqualTo(8192);
                    assertThat(properties.completionTimeout()).isEqualTo(Duration.ofSeconds(120));
                });
    }

    @Test
    void configuredFallbackIsWiredIntoRuntimeRouter() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.fallback-provider=ark",
                        "pulseink.model.ark.api-key=test-key",
                        "pulseink.model.ark.base-url=https://ark.example/api/v3",
                        "pulseink.model.ark.model=doubao-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var profile = AgentProfile.unified(
                            "unified",
                            Set.of(),
                            new ModelPolicy(List.of("fake", "ark"), Set.of()),
                            ExecutionBudget.defaultReact(
                                    Instant.now().plus(Duration.ofMinutes(30))));

                    assertThat(context.getBean(ModelRouter.class)
                            .route(profile, Set.of("fake"))
                            .providerId()).isEqualTo("ark");
                });
    }
}
