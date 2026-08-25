package com.pulseink.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pulseink.client.embedding.DeterministicFakeEmbeddingAdapter;
import com.pulseink.client.embedding.OpenAiCompatibleEmbeddingAdapter;
import com.pulseink.client.search.ElasticsearchRetrievalAdapter;
import com.pulseink.client.search.KnowledgeIndexNaming;
import com.pulseink.client.tool.KnowledgeSearchTool;
import com.pulseink.config.properties.EmbeddingProperties;
import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingPurpose;
import com.pulseink.service.knowledge.KnowledgeDocumentRepository;
import com.pulseink.service.knowledge.KnowledgeSearchService;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import com.pulseink.service.knowledge.RetrievalStore;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditional embedding wiring. Fake is the default and requires no secrets; the
 * openai-compatible provider is only assembled when its full public configuration is present.
 * The retrieval store and search service are business-neutral and never connect during startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmbeddingProperties.class, KnowledgeProperties.class})
public class KnowledgeConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "pulseink.embedding.provider",
            havingValue = "fake",
            matchIfMissing = true)
    EmbeddingPort fakeEmbeddingPort(EmbeddingProperties properties) {
        return new DeterministicFakeEmbeddingAdapter(properties.dimensions());
    }

    @Bean
    @ConditionalOnProperty(
            name = "pulseink.embedding.provider",
            havingValue = "openai-compatible")
    EmbeddingPort openAiEmbeddingPort(EmbeddingProperties properties) {
        requireText(properties.baseUrl(),
                "PULSEINK_EMBEDDING_BASE_URL must not be blank when provider is openai-compatible");
        requireText(properties.apiKey(),
                "PULSEINK_EMBEDDING_API_KEY must not be blank when provider is openai-compatible");
        requireText(properties.model(),
                "PULSEINK_EMBEDDING_MODEL must not be blank when provider is openai-compatible");
        return new OpenAiCompatibleEmbeddingAdapter(
                properties.baseUrl(),
                properties.apiKey(),
                properties.model(),
                properties.dimensions(),
                properties.dimensionField(),
                properties.batchSize(),
                properties.timeout());
    }

    @Bean
    KnowledgeIndexNaming knowledgeIndexNaming(KnowledgeProperties properties) {
        return new KnowledgeIndexNaming(properties.indexAlias());
    }

    @Bean
    RetrievalStore retrievalStore(ElasticsearchClient elasticsearchClient,
                                  KnowledgeIndexNaming knowledgeIndexNaming,
                                  EmbeddingPort embeddingPort) {
        var port = embeddingPort;
        return new ElasticsearchRetrievalAdapter(
                elasticsearchClient,
                knowledgeIndexNaming,
                new ElasticsearchRetrievalAdapter.EmbeddingPortAdapter() {
                    @Override
                    public String providerId() {
                        return port.profile().providerId();
                    }

                    @Override
                    public String modelId() {
                        return port.profile().modelId();
                    }

                    @Override
                    public int dimensions() {
                        return port.profile().dimensions();
                    }

                    @Override
                    public float[] embedQuery(String text) {
                        return port.embed(List.of(text), EmbeddingPurpose.QUERY)
                                .vectors().get(0);
                    }
                });
    }

    @Bean
    KnowledgeSearchService knowledgeSearchService(RetrievalStore retrievalStore,
                                                  KnowledgeDocumentRepository knowledgeDocumentRepository,
                                                  KnowledgeProperties properties) {
        return new KnowledgeSearchService(
                retrievalStore,
                knowledgeDocumentRepository,
                properties.rrfConstant(),
                properties.snippetMaxCodePoints());
    }

    @Bean
    KnowledgeSearchTool knowledgeSearchTool(QueryKnowledgeUseCase queryKnowledgeUseCase) {
        return new KnowledgeSearchTool(queryKnowledgeUseCase);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
