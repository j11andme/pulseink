package com.pulseink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.client.embedding.DeterministicFakeEmbeddingAdapter;
import com.pulseink.config.properties.AgentRuntimeProperties;
import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.config.properties.RunLeaseProperties;
import com.pulseink.service.campaign.RunJournal;
import com.pulseink.service.campaign.RunLeaseManager;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.memory.CampaignInsightRepository;
import com.pulseink.service.memory.InsightIndexWorker;
import com.pulseink.service.memory.InsightSearchStore;
import com.pulseink.service.memory.MemoryPort;
import com.pulseink.service.memory.MemorySourceRepository;
import com.pulseink.service.memory.RunWorkingMemoryCache;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Assembly test for the memory wiring: all adapters and the index worker start under fake
 * model/embedding configuration, and the context also starts with the index worker and the
 * run lease disabled while remaining enabled by default in production.
 */
class MemoryApplicationContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues(
                            "pulseink.model.provider=fake",
                            "pulseink.embedding.provider=fake",
                            "pulseink.memory.index-worker-enabled=true",
                            "pulseink.run-lease.enabled=true")
                    .withBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class,
                            () -> mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class))
                    .withBean(EmbeddingPort.class, DeterministicFakeEmbeddingAdapter::new)
                    .withBean(ModelRouter.class, () -> new ModelRouter(List.of()))
                    .withBean("runtimeModelPolicy", ModelPolicy.class,
                            () -> new ModelPolicy(List.of("fake"), Set.of()))
                    .withBean(AgentRuntimeProperties.class,
                            () -> new AgentRuntimeProperties(4096, Duration.ofSeconds(90)))
                    .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                            () -> new com.fasterxml.jackson.databind.ObjectMapper()
                                    .findAndRegisterModules())
                    .withBean(StringRedisTemplate.class,
                            () -> mock(StringRedisTemplate.class))
                    .withBean(RunJournal.class, () -> mock(RunJournal.class))
                    .withBean(MemorySourceRepository.class,
                            () -> mock(MemorySourceRepository.class))
                    .withBean(CampaignInsightRepository.class,
                            () -> mock(CampaignInsightRepository.class))
                    .withUserConfiguration(MemoryConfiguration.class);

    @Test
    void wiresMemoryAdaptersWorkerAndLeaseWithDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MemoryProperties.class);
            assertThat(context).hasSingleBean(RunLeaseProperties.class);
            assertThat(context).hasSingleBean(InsightSearchStore.class);
            assertThat(context).hasSingleBean(MemoryPort.class);
            assertThat(context).hasSingleBean(RunWorkingMemoryCache.class);
            assertThat(context).hasSingleBean(InsightIndexWorker.class);
            assertThat(context).hasSingleBean(RunLeaseManager.class);
            assertThat(context).hasSingleBean(MemoryConfiguration.InsightIndexPolling.class);
        });
    }

    @Test
    void startsWithWorkerAndLeaseDisabledButStillWiresAdapters() {
        contextRunner
                .withPropertyValues(
                        "pulseink.memory.index-worker-enabled=false",
                        "pulseink.run-lease.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InsightSearchStore.class);
                    assertThat(context).hasSingleBean(MemoryPort.class);
                    assertThat(context).hasSingleBean(RunLeaseManager.class);
                    assertThat(context).doesNotHaveBean(
                            MemoryConfiguration.InsightIndexPolling.class);
                });
    }
}
