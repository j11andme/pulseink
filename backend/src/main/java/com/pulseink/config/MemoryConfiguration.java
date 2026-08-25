package com.pulseink.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.client.model.ModelInsightCandidateGenerator;
import com.pulseink.client.search.ElasticsearchInsightStore;
import com.pulseink.config.properties.AgentRuntimeProperties;
import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.config.properties.RunLeaseProperties;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingPurpose;
import com.pulseink.service.memory.CampaignInsightRepository;
import com.pulseink.service.memory.InsightIndexWorker;
import com.pulseink.service.memory.InsightSearchStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Memory infrastructure: properties, the derived ES insight store, the index worker and the
 * insight model generator. Business rules live in services; this class only wires adapters.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MemoryProperties.class, RunLeaseProperties.class})
public class MemoryConfiguration {

    @Bean
    Clock memoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    InsightSearchStore insightSearchStore(ElasticsearchClient elasticsearchClient,
                                          MemoryProperties properties,
                                          EmbeddingPort embeddingPort) {
        return new ElasticsearchInsightStore(elasticsearchClient, properties.indexAlias(),
                new ElasticsearchInsightStore.EmbeddingAdapter() {
                    @Override
                    public com.pulseink.service.embedding.EmbeddingProfile profile() {
                        return embeddingPort.profile();
                    }

                    @Override
                    public float[] embed(String text) {
                        return embeddingPort.embed(List.of(text), EmbeddingPurpose.QUERY)
                                .vectors().get(0);
                    }
                });
    }

    @Bean
    com.pulseink.service.memory.InsightCandidateGenerator insightCandidateGenerator(
            ModelRouter modelRouter,
            @Qualifier("runtimeModelPolicy") com.pulseink.agent.model.ModelPolicy modelPolicy,
            AgentRuntimeProperties properties,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ModelInsightCandidateGenerator(
                modelRouter,
                modelPolicy,
                properties.maxOutputTokensPerCall(),
                properties.completionTimeout(),
                objectMapper);
    }

    @Bean
    com.pulseink.service.memory.RunWorkingMemoryCache runWorkingMemoryCache(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            MemoryProperties properties) {
        return new com.pulseink.repository.cache.RedisRunWorkingMemoryCache(
                redisTemplate, objectMapper, properties.runCacheTtl());
    }

    @Bean
    com.pulseink.service.memory.MemoryPort memoryPort(
            com.pulseink.service.campaign.RunJournal journal,
            com.pulseink.service.memory.MemorySourceRepository sourceRepository,
            InsightSearchStore searchStore,
            com.pulseink.service.memory.RunWorkingMemoryCache cache,
            MemoryProperties properties) {
        return new com.pulseink.service.memory.DefaultMemoryPort(
                journal, sourceRepository, searchStore, cache, properties.approvedTopK());
    }

    @Bean
    com.pulseink.service.campaign.RunLeasePort runLeasePort(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        return new com.pulseink.repository.cache.RedisRunLeaseAdapter(redisTemplate);
    }

    @Bean(destroyMethod = "close")
    com.pulseink.service.campaign.RunLeaseManager runLeaseManager(
            com.pulseink.service.campaign.RunLeasePort runLeasePort,
            RunLeaseProperties properties,
            org.springframework.core.env.Environment environment) {
        return new com.pulseink.service.campaign.RunLeaseManager(
                runLeasePort, properties, ownerId(properties, environment));
    }

    private static String ownerId(RunLeaseProperties properties,
                                  org.springframework.core.env.Environment environment) {
        if (properties.ownerId() != null && !properties.ownerId().isBlank()) {
            return properties.ownerId();
        }
        String appName = environment.getProperty(
                "spring.application.name", "pulseink");
        String host = hostName();
        long pid = ProcessHandle.current().pid();
        return appName + "@" + host + "/" + pid + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException exception) {
            return "unknown-host";
        }
    }

    @Bean
    InsightIndexWorker insightIndexWorker(CampaignInsightRepository repository,
                                          InsightSearchStore store,
                                          MemoryProperties properties,
                                          Clock memoryClock) {
        return new InsightIndexWorker(repository, store, properties, memoryClock);
    }

    @Bean
    @ConditionalOnProperty(name = "pulseink.memory.index-worker-enabled",
            havingValue = "true", matchIfMissing = false)
    InsightIndexPolling insightIndexPolling(InsightIndexWorker worker) {
        return new InsightIndexPolling(worker);
    }

    public static final class InsightIndexPolling {

        private final InsightIndexWorker worker;

        InsightIndexPolling(InsightIndexWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${pulseink.memory.index-worker-delay:2s}",
                initialDelayString = "${pulseink.memory.index-worker-delay:2s}")
        public void poll() {
            worker.processBatch();
        }
    }
}
