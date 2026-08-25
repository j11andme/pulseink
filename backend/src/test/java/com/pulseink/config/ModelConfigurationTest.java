package com.pulseink.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.service.model.ChatWithModelUseCase;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.OpenAiCompatibleModelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ModelConfiguration.class);

    @Test
    void fakeIsTheDefaultProviderAndNeedsNoSecret() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentModelPort.class);
            assertThat(context.getBean(AgentModelPort.class))
                    .isInstanceOf(FakeModelAdapter.class);
            assertThat(context).hasSingleBean(ChatWithModelUseCase.class);
            assertThat(context).doesNotHaveBean(StreamingChatModel.class);
        });
    }

    @Test
    void arkProviderRejectsABlankApiKey() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=ark",
                        "pulseink.model.ark.api-key=",
                        "pulseink.model.ark.model=doubao-test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("ARK_API_KEY must not be blank when provider is ark");
                });
    }

    @Test
    void arkProviderRejectsABlankModel() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=ark",
                        "pulseink.model.ark.api-key=test-key",
                        "pulseink.model.ark.model=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("ARK_MODEL must not be blank when provider is ark");
                });
    }

    @Test
    void arkProviderBuildsTheAdapterWithoutCallingTheNetwork() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=ark",
                        "pulseink.model.ark.api-key=test-key",
                        "pulseink.model.ark.base-url=https://ark.example/api/v3",
                        "pulseink.model.ark.model=doubao-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StreamingChatModel.class);
                    assertThat(context.getBean(AgentModelPort.class))
                            .isInstanceOf(OpenAiCompatibleModelAdapter.class);
                    assertThat(context).hasSingleBean(ChatWithModelUseCase.class);
                });
    }

    @Test
    void zhipuProviderBuildsAnOpenAiCompatibleAdapterWithoutCallingTheNetwork() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=zhipu",
                        "pulseink.model.zhipu.api-key=test-key",
                        "pulseink.model.zhipu.base-url=https://open.bigmodel.cn/api/paas/v4",
                        "pulseink.model.zhipu.model=glm-5.2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StreamingChatModel.class);
                    assertThat(context.getBean(AgentModelPort.class))
                            .isInstanceOf(OpenAiCompatibleModelAdapter.class);
                    assertThat(context).hasSingleBean(ChatWithModelUseCase.class);
                });
    }

    @Test
    void zhipuProviderRejectsABlankApiKey() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=zhipu",
                        "pulseink.model.zhipu.api-key=",
                        "pulseink.model.zhipu.model=glm-5.2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "ZHIPU_API_KEY must not be blank when provider is zhipu");
                });
    }

    @Test
    void fakePrimaryWithoutFallbackHasSinglePortBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentModelPort.class);
            assertThat(context.getBean(AgentModelPort.class))
                    .isInstanceOf(FakeModelAdapter.class);
        });
    }

    @Test
    void fallbackConfiguredAddsNamedFallbackPort() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=fake",
                        "pulseink.model.fallback-provider=ark",
                        "pulseink.model.ark.api-key=test-key",
                        "pulseink.model.ark.base-url=https://ark.example/api/v3",
                        "pulseink.model.ark.model=doubao-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("primaryModelPort", AgentModelPort.class))
                            .isInstanceOf(FakeModelAdapter.class);
                    assertThat(context.getBean("fallbackModelPort", AgentModelPort.class))
                            .isInstanceOf(OpenAiCompatibleModelAdapter.class);
                    assertThat(context).hasSingleBean(ChatWithModelUseCase.class);
                });
    }

    @Test
    void fallbackEqualToPrimaryFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=zhipu",
                        "pulseink.model.fallback-provider=zhipu",
                        "pulseink.model.zhipu.api-key=test-key",
                        "pulseink.model.zhipu.base-url=https://open.bigmodel.cn/api/paas/v4",
                        "pulseink.model.zhipu.model=glm-5.2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "fallback provider must differ from primary provider");
                });
    }

    @Test
    void fallbackRequiresFullPublicConfiguration() {
        contextRunner
                .withPropertyValues(
                        "pulseink.model.provider=fake",
                        "pulseink.model.fallback-provider=ark",
                        "pulseink.model.ark.api-key=",
                        "pulseink.model.ark.model=doubao-test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("ARK_API_KEY must not be blank when provider is ark");
                });
    }
}
