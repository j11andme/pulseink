package com.pulseink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.client.embedding.DeterministicFakeEmbeddingAdapter;
import com.pulseink.client.embedding.OpenAiCompatibleEmbeddingAdapter;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.knowledge.KnowledgeDocumentRepository;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KnowledgeConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class,
                            () -> org.mockito.Mockito.mock(
                                    co.elastic.clients.elasticsearch.ElasticsearchClient.class))
                    .withBean(KnowledgeDocumentRepository.class,
                            () -> org.mockito.Mockito.mock(KnowledgeDocumentRepository.class))
                    .withBean(QueryKnowledgeUseCase.class,
                            () -> org.mockito.Mockito.mock(QueryKnowledgeUseCase.class))
                    .withUserConfiguration(KnowledgeConfiguration.class);

    @Test
    void fakeIsTheDefaultEmbeddingProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingPort.class);
            assertThat(context.getBean(EmbeddingPort.class))
                    .isInstanceOf(DeterministicFakeEmbeddingAdapter.class);
        });
    }

    @Test
    void openAiCompatibleProviderIsAssembledWithFullConfig() {
        contextRunner
                .withPropertyValues(
                        "pulseink.embedding.provider=openai-compatible",
                        "pulseink.embedding.base-url=https://example.com/v1",
                        "pulseink.embedding.api-key=test-key",
                        "pulseink.embedding.model=embed-3",
                        "pulseink.embedding.dimensions=128")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingPort.class))
                            .isInstanceOf(OpenAiCompatibleEmbeddingAdapter.class);
                });
    }

    @Test
    void openAiCompatibleProviderFailsWhenConfigIsIncomplete() {
        contextRunner
                .withPropertyValues(
                        "pulseink.embedding.provider=openai-compatible",
                        "pulseink.embedding.api-key=test-key",
                        "pulseink.embedding.model=embed-3")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "PULSEINK_EMBEDDING_BASE_URL must not be blank when provider is openai-compatible");
                });
    }

    @Test
    void fakeProviderIgnoresMissingRealConfig() {
        contextRunner
                .withPropertyValues("pulseink.embedding.provider=fake")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingPort.class))
                            .isInstanceOf(DeterministicFakeEmbeddingAdapter.class);
                });
    }
}
