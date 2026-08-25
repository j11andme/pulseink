package com.pulseink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.selection.RuleBasedExecutionModeSelector;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.service.evaluation.EvaluationCaseCatalog;
import com.pulseink.service.evaluation.EvaluationPolicyExecutor;
import com.pulseink.service.evaluation.RunEvaluationUseCase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EvaluationConfigurationTest {

    @Test
    void wiresEvaluationWithoutChangingTheProductionAgentBeans() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "pulseink.evaluation.root=../evals",
                        "pulseink.evaluation.report-root=target/evaluation-config-reports")
                .withBean(ObjectMapper.class,
                        () -> new ObjectMapper().findAndRegisterModules())
                .withBean("primaryModelPort", AgentModelPort.class,
                        () -> mock(AgentModelPort.class))
                .withBean(com.pulseink.agent.selection.ExecutionModeSelector.class,
                        RuleBasedExecutionModeSelector::new)
                .withBean(ModelProperties.class,
                        () -> new ModelProperties("fake", "", null, null, null))
                .withBean(ModelRouter.class, () -> new ModelRouter(List.of(
                        new ModelRoute("fake", "pulseink-fake", Set.of(),
                                FakeModelAdapter.fast()))))
                .withBean("runtimeModelPolicy", ModelPolicy.class,
                        () -> new ModelPolicy(List.of("fake"), Set.of()))
                .withUserConfiguration(EvaluationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EvaluationCaseCatalog.class);
                    assertThat(context).hasSingleBean(EvaluationPolicyExecutor.class);
                    assertThat(context).hasSingleBean(RunEvaluationUseCase.class);
                    assertThat(context.getBean(EvaluationCaseCatalog.class).all()).hasSize(18);
                });
    }
}
