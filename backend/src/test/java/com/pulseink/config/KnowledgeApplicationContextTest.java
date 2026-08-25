package com.pulseink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pulseink.service.auth.AccessTokenIssuer;
import com.pulseink.service.auth.PasswordVerifier;
import com.pulseink.service.auth.UserAccountRepository;
import com.pulseink.service.campaign.CampaignRepository;
import com.pulseink.service.campaign.RunJournal;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.content.ContentWorkflowRepository;
import com.pulseink.client.tool.KnowledgeSearchTool;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.service.knowledge.IngestKnowledgeUseCase;
import com.pulseink.service.knowledge.IngestionJobRepository;
import com.pulseink.service.knowledge.KnowledgeDocumentRepository;
import com.pulseink.service.knowledge.KnowledgeIngestionCoordinator;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import com.pulseink.service.knowledge.RetrievalStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class KnowledgeApplicationContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ElasticsearchRestClientAutoConfiguration.class,
                            ElasticsearchClientAutoConfiguration.class))
                    .withPropertyValues(
                            "pulseink.model.provider=fake",
                            "pulseink.embedding.provider=fake",
                            "spring.elasticsearch.uris=http://localhost:59998",
                            "pulseink.knowledge.storage-root=./target/knowledge-ctx")
                    .withBean(UserAccountRepository.class,
                            () -> mock(UserAccountRepository.class))
                    .withBean(PasswordVerifier.class,
                            () -> mock(PasswordVerifier.class))
                    .withBean(AccessTokenIssuer.class,
                            () -> mock(AccessTokenIssuer.class))
                    .withBean(CampaignRepository.class,
                            () -> mock(CampaignRepository.class))
                    .withBean(RunRepository.class,
                            () -> mock(RunRepository.class))
                    .withBean(RunJournal.class,
                            () -> mock(RunJournal.class))
                    .withBean(ContentWorkflowRepository.class,
                            () -> mock(ContentWorkflowRepository.class))
                    .withBean(com.pulseink.service.publishing.PublicationRepository.class,
                            () -> mock(com.pulseink.service.publishing.PublicationRepository.class))
                    .withBean(com.pulseink.service.feedback.FeedbackRepository.class,
                            () -> mock(com.pulseink.service.feedback.FeedbackRepository.class))
                    .withBean(com.pulseink.service.memory.MemorySourceRepository.class,
                            () -> mock(com.pulseink.service.memory.MemorySourceRepository.class))
                    .withBean(com.pulseink.service.memory.CampaignInsightRepository.class,
                            () -> mock(com.pulseink.service.memory.CampaignInsightRepository.class))
                    .withBean(com.pulseink.service.memory.InsightCandidateGenerator.class,
                            () -> mock(com.pulseink.service.memory.InsightCandidateGenerator.class))
                    .withBean(com.pulseink.service.memory.InsightSearchStore.class,
                            () -> mock(com.pulseink.service.memory.InsightSearchStore.class))
                    .withBean(com.pulseink.config.properties.MemoryProperties.class,
                            () -> new com.pulseink.config.properties.MemoryProperties(
                                    null, null, null, null, null, null, null, null))
                    .withBean(com.pulseink.service.memory.MemoryPort.class,
                            () -> mock(com.pulseink.service.memory.MemoryPort.class))
                    .withBean(com.pulseink.service.campaign.RunLeaseManager.class,
                            () -> mock(com.pulseink.service.campaign.RunLeaseManager.class))
                    .withBean(java.time.Clock.class, () -> java.time.Clock.systemUTC())
                    .withBean(KnowledgeDocumentRepository.class,
                            () -> mock(KnowledgeDocumentRepository.class))
                    .withBean(IngestionJobRepository.class,
                            () -> mock(IngestionJobRepository.class))
                    .withBean(PlatformTransactionManager.class,
                            () -> mock(PlatformTransactionManager.class))
                    .withUserConfiguration(
                            KnowledgeConfiguration.class,
                            AgentRuntimeConfiguration.class,
                            ModelConfiguration.class,
                            ApplicationServiceConfiguration.class);

    @Test
    void applicationStartsWithoutElasticsearchAndWiresKnowledgeBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IngestKnowledgeUseCase.class);
            assertThat(context).hasSingleBean(QueryKnowledgeUseCase.class);
            assertThat(context).hasSingleBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class);
            assertThat(context).hasSingleBean(RetrievalStore.class);
            assertThat(context).hasSingleBean(KnowledgeIngestionCoordinator.class);
            assertThat(context).hasSingleBean(KnowledgeRecoveryStartup.class);
            assertThat(context).hasSingleBean(KnowledgeSearchTool.class);
            assertThat(context).hasSingleBean(JavaToolProvider.class);
            assertThat(context.getBean(JavaToolProvider.class).discover())
                    .extracting(t -> t.qualifiedName())
                    .contains("builtin.knowledge_search", "builtin.deterministic_validate");
        });
    }
}
