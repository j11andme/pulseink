package com.pulseink.config;

import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.selection.ExecutionModeSelector;
import com.pulseink.agent.selection.RuleBasedExecutionModeSelector;
import com.pulseink.client.document.TikaDocumentTextExtractor;
import com.pulseink.client.search.ElasticsearchRetrievalAdapter;
import com.pulseink.client.storage.LocalVolumeDocumentStore;
import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.service.auth.AccessTokenIssuer;
import com.pulseink.service.auth.AuthenticateUserService;
import com.pulseink.service.auth.AuthenticateUserUseCase;
import com.pulseink.service.auth.PasswordVerifier;
import com.pulseink.service.auth.UserAccountRepository;
import com.pulseink.service.campaign.CampaignApplicationService;
import com.pulseink.service.campaign.CampaignRepository;
import com.pulseink.service.campaign.RunApplicationService;
import com.pulseink.service.campaign.RunEventService;
import com.pulseink.service.campaign.RunExecutionService;
import com.pulseink.service.campaign.RunJournal;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.content.CaptureRunContentUseCase;
import com.pulseink.service.content.ContentWorkflowRepository;
import com.pulseink.service.content.ContentWorkflowService;
import com.pulseink.service.knowledge.DocumentTextExtractor;
import com.pulseink.service.knowledge.HeadingAwareChunker;
import com.pulseink.service.knowledge.IngestKnowledgeUseCase;
import com.pulseink.service.knowledge.IngestionJobRepository;
import com.pulseink.service.knowledge.KnowledgeDocumentRepository;
import com.pulseink.service.knowledge.KnowledgeIngestionCoordinator;
import com.pulseink.service.knowledge.KnowledgeIngestionService;
import com.pulseink.service.knowledge.KnowledgeSearchService;
import com.pulseink.service.knowledge.OriginalDocumentStore;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import com.pulseink.service.knowledge.RetrievalStore;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ApplicationServiceConfiguration {

    @Bean
    AuthenticateUserUseCase authenticateUserUseCase(
            UserAccountRepository userAccountRepository,
            PasswordVerifier passwordVerifier,
            AccessTokenIssuer accessTokenIssuer) {
        return new AuthenticateUserService(
                userAccountRepository,
                passwordVerifier,
                accessTokenIssuer);
    }

    @Bean
    CampaignApplicationService campaignApplicationService(
            CampaignRepository campaignRepository) {
        return new CampaignApplicationService(campaignRepository);
    }

    @Bean
    ExecutionModeSelector executionModeSelector() {
        return new RuleBasedExecutionModeSelector();
    }

    @Bean
    RunApplicationService runApplicationService(
            CampaignRepository campaignRepository,
            RunRepository runRepository,
            ExecutionModeSelector executionModeSelector,
            RunJournal runJournal) {
        return new RunApplicationService(
                campaignRepository, runRepository, executionModeSelector, runJournal);
    }

    @Bean
    RunEventService runEventService(RunJournal runJournal) {
        return new RunEventService(runJournal);
    }

    @Bean(destroyMethod = "close")
    Executor runExecutionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    RunExecutionService runExecutionService(
            RunRepository runRepository,
            CampaignRepository campaignRepository,
            RunEventService runEventService,
            RunJournal runJournal,
            DirectAgentEngine directAgentEngine,
            UnifiedAgentRunner unifiedAgentRunner,
            com.pulseink.agent.orchestration.RunCoordinator runCoordinator,
            CaptureRunContentUseCase captureRunContentUseCase,
            ModelPolicy runtimeModelPolicy,
            Executor runExecutionExecutor,
            com.pulseink.agent.memory.ContextAssembler contextAssembler,
            com.pulseink.config.properties.MemoryProperties memoryProperties,
            com.pulseink.service.campaign.RunLeaseManager runLeaseManager) {
        return new RunExecutionService(
                runRepository,
                campaignRepository,
                runEventService,
                runJournal,
                directAgentEngine,
                unifiedAgentRunner,
                runCoordinator,
                captureRunContentUseCase,
                runtimeModelPolicy,
                runExecutionExecutor,
                contextAssembler,
                memoryProperties,
                runLeaseManager);
    }

    @Bean
    ContentWorkflowService contentWorkflowService(
            ContentWorkflowRepository contentWorkflowRepository,
            RunRepository runRepository,
            com.pulseink.agent.repair.ReviewArtifactInterpreter reviewArtifactInterpreter,
            com.pulseink.agent.plan.PlanParser planParser,
            PlatformTransactionManager transactionManager) {
        return new ContentWorkflowService(
                contentWorkflowRepository,
                runRepository,
                reviewArtifactInterpreter,
                planParser,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    com.pulseink.service.publishing.PublicationService publicationService(
            com.pulseink.service.publishing.PublicationRepository publicationRepository,
            ContentWorkflowRepository contentWorkflowRepository,
            RunRepository runRepository,
            PlatformTransactionManager transactionManager,
            java.time.Clock publicationClock) {
        return new com.pulseink.service.publishing.PublicationService(
                publicationRepository,
                contentWorkflowRepository,
                runRepository,
                new TransactionTemplate(transactionManager),
                publicationClock);
    }

    @Bean
    com.pulseink.service.feedback.FeedbackIngestionService feedbackIngestionService(
            com.pulseink.service.feedback.FeedbackRepository feedbackRepository,
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Value(
                    "${pulseink.business-zone:Asia/Shanghai}") String businessZone) {
        return new com.pulseink.service.feedback.FeedbackIngestionService(
                feedbackRepository,
                new TransactionTemplate(transactionManager),
                java.time.ZoneId.of(businessZone));
    }

    @Bean
    com.pulseink.service.memory.ConsolidateInsightService consolidateInsightService(
            com.pulseink.service.memory.MemorySourceRepository memorySourceRepository,
            com.pulseink.service.memory.CampaignInsightRepository campaignInsightRepository,
            com.pulseink.service.memory.InsightCandidateGenerator insightCandidateGenerator,
            PlatformTransactionManager transactionManager,
            java.time.Clock memoryClock) {
        return new com.pulseink.service.memory.ConsolidateInsightService(
                memorySourceRepository,
                campaignInsightRepository,
                insightCandidateGenerator,
                new TransactionTemplate(transactionManager),
                memoryClock);
    }

    @Bean
    com.pulseink.service.memory.ReviewInsightService reviewInsightService(
            com.pulseink.service.memory.CampaignInsightRepository campaignInsightRepository,
            PlatformTransactionManager transactionManager,
            java.time.Clock memoryClock) {
        return new com.pulseink.service.memory.ReviewInsightService(
                campaignInsightRepository,
                new TransactionTemplate(transactionManager),
                memoryClock);
    }

    @Bean
    com.pulseink.service.memory.QueryInsightService queryInsightService(
            com.pulseink.service.memory.CampaignInsightRepository campaignInsightRepository,
            com.pulseink.service.memory.InsightSearchStore insightSearchStore,
            com.pulseink.config.properties.MemoryProperties memoryProperties) {
        return new com.pulseink.service.memory.QueryInsightService(
                campaignInsightRepository,
                insightSearchStore,
                memoryProperties);
    }

    @Bean
    OriginalDocumentStore originalDocumentStore(KnowledgeProperties properties) {
        return new LocalVolumeDocumentStore(properties.storageRoot());
    }

    @Bean
    DocumentTextExtractor documentTextExtractor() {
        return new TikaDocumentTextExtractor();
    }

    @Bean
    HeadingAwareChunker headingAwareChunker(KnowledgeProperties properties) {
        return new HeadingAwareChunker(
                properties.maxChunkCodePoints(),
                properties.chunkOverlap(),
                properties.maxChunks());
    }

    @Bean(destroyMethod = "close")
    KnowledgeIngestionCoordinator knowledgeIngestionCoordinator(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            IngestionJobRepository ingestionJobRepository,
            OriginalDocumentStore originalDocumentStore,
            DocumentTextExtractor documentTextExtractor,
            HeadingAwareChunker headingAwareChunker,
            EmbeddingPort embeddingPort,
            RetrievalStore retrievalStore,
            KnowledgeProperties properties,
            PlatformTransactionManager transactionManager) {
        return new KnowledgeIngestionCoordinator.Default(
                knowledgeDocumentRepository,
                ingestionJobRepository,
                originalDocumentStore,
                documentTextExtractor,
                headingAwareChunker,
                embeddingPort,
                retrievalStore,
                properties,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    KnowledgeIngestionService knowledgeIngestionService(
            OriginalDocumentStore originalDocumentStore,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            IngestionJobRepository ingestionJobRepository,
            DocumentTextExtractor documentTextExtractor,
            KnowledgeIngestionCoordinator knowledgeIngestionCoordinator,
            KnowledgeSearchService knowledgeSearchService,
            EmbeddingPort embeddingPort,
            PlatformTransactionManager transactionManager) {
        return new KnowledgeIngestionService(
                originalDocumentStore,
                knowledgeDocumentRepository,
                ingestionJobRepository,
                documentTextExtractor,
                knowledgeIngestionCoordinator,
                knowledgeSearchService,
                embeddingPort,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    KnowledgeRecoveryStartup knowledgeRecoveryStartup(
            KnowledgeIngestionCoordinator knowledgeIngestionCoordinator) {
        return new KnowledgeRecoveryStartup(knowledgeIngestionCoordinator);
    }
}
