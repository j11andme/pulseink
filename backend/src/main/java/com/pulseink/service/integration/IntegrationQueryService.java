package com.pulseink.service.integration;

import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.config.ModelProperties;
import com.pulseink.config.properties.ChannelProperties;
import com.pulseink.config.properties.EmbeddingProperties;
import com.pulseink.config.properties.FeedbackProperties;
import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.config.properties.PublicationProperties;
import com.pulseink.config.properties.RunLeaseProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Static integration status assembler. Values are derived from application configuration and the
 * tool registry snapshot; no network probe, provider, credential or full URI ever reaches the
 * response.
 */
public class IntegrationQueryService implements QueryIntegrationUseCase {

    public static final String CONFIGURED = "CONFIGURED";
    public static final String DISABLED = "DISABLED";

    private static final String CATEGORY_MODEL = "MODEL";
    private static final String CATEGORY_RETRIEVAL = "RETRIEVAL";
    private static final String CATEGORY_RUNTIME = "RUNTIME";
    private static final String CATEGORY_MESSAGING = "MESSAGING";
    private static final String CATEGORY_PUBLISHING = "PUBLISHING";
    private static final String CATEGORY_TOOLS = "TOOLS";

    private final ModelProperties modelProperties;
    private final EmbeddingProperties embeddingProperties;
    private final KnowledgeProperties knowledgeProperties;
    private final MemoryProperties memoryProperties;
    private final RunLeaseProperties runLeaseProperties;
    private final ChannelProperties channelProperties;
    private final PublicationProperties publicationProperties;
    private final FeedbackProperties feedbackProperties;
    private final ToolRegistry toolRegistry;

    public IntegrationQueryService(ModelProperties modelProperties,
                                   EmbeddingProperties embeddingProperties,
                                   KnowledgeProperties knowledgeProperties,
                                   MemoryProperties memoryProperties,
                                   RunLeaseProperties runLeaseProperties,
                                   ChannelProperties channelProperties,
                                   PublicationProperties publicationProperties,
                                   FeedbackProperties feedbackProperties,
                                   ToolRegistry toolRegistry) {
        this.modelProperties = Objects.requireNonNull(modelProperties);
        this.embeddingProperties = Objects.requireNonNull(embeddingProperties);
        this.knowledgeProperties = Objects.requireNonNull(knowledgeProperties);
        this.memoryProperties = Objects.requireNonNull(memoryProperties);
        this.runLeaseProperties = Objects.requireNonNull(runLeaseProperties);
        this.channelProperties = Objects.requireNonNull(channelProperties);
        this.publicationProperties = Objects.requireNonNull(publicationProperties);
        this.feedbackProperties = Objects.requireNonNull(feedbackProperties);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
    }

    @Override
    public IntegrationStatus status() {
        var integrations = new ArrayList<Integration>();
        integrations.add(modelIntegration());
        integrations.add(embeddingIntegration());
        integrations.add(knowledgeElasticsearchIntegration());
        integrations.add(memoryRedisIntegration());
        integrations.add(kafkaFeedbackIntegration());
        integrations.add(channelSandboxIntegration());
        integrations.add(toolRegistryIntegration());
        var tools = toolRegistry.definitionSnapshot().stream()
                .map(definition -> new Tool(
                        definition.qualifiedName(),
                        definition.risk().name(),
                        definition.description()))
                .toList();
        return new IntegrationStatus(List.copyOf(integrations), tools);
    }

    private Integration modelIntegration() {
        String provider = safeProvider(modelProperties.provider(), "fake");
        String fallback = modelProperties.fallbackProvider();
        boolean hasFallback = fallback != null && !fallback.isBlank()
                && !fallback.equals(provider);
        String summary = "fake".equals(provider)
                ? "本地演示 Provider：fake，不依赖外部 API Key"
                : "已配置外部模型 Provider（" + provider + "），API Key 由后端环境管理";
        return new Integration(
                "model",
                "模型 Provider",
                CATEGORY_MODEL,
                CONFIGURED,
                summary,
                hasFallback
                        ? List.of("DIRECT / REACT / ORCHESTRATED 统一模型端口",
                                "已配置故障回退 Provider")
                        : List.of("DIRECT / REACT / ORCHESTRATED 统一模型端口",
                                "未配置故障回退 Provider"));
    }

    private Integration embeddingIntegration() {
        String provider = safeProvider(embeddingProperties.provider(), "fake");
        String summary = "fake".equals(provider)
                ? "本地确定性 Embedding，无需外部密钥"
                : "已配置外部 Embedding Provider，密钥由后端环境管理";
        return new Integration(
                "embedding",
                "Embedding Provider",
                CATEGORY_RETRIEVAL,
                CONFIGURED,
                summary,
                List.of("知识切片向量化", "查询向量化"));
    }

    private Integration knowledgeElasticsearchIntegration() {
        return new Integration(
                "knowledge-elasticsearch",
                "Elasticsearch Knowledge",
                CATEGORY_RETRIEVAL,
                CONFIGURED,
                "Spring Elasticsearch 自动配置提供 Knowledge 索引，支持 BM25 + KNN + RRF",
                List.of("混合检索", "Metadata Filter", "Knowledge Alias 切换"));
    }

    private Integration memoryRedisIntegration() {
        var capabilities = new ArrayList<String>();
        capabilities.add("可重建 Run Working Memory 缓存");
        capabilities.add(Boolean.TRUE.equals(runLeaseProperties.enabled())
                ? "Run Lease 防多实例接管"
                : "Run Lease 已禁用");
        return new Integration(
                "memory-redis",
                "Redis Memory / Lease",
                CATEGORY_RUNTIME,
                CONFIGURED,
                "Redis 提供可重建缓存与运行所有权协调，MySQL 仍是权威源",
                List.copyOf(capabilities));
    }

    private Integration kafkaFeedbackIntegration() {
        boolean enabled = Boolean.TRUE.equals(feedbackProperties.consumerEnabled());
        return new Integration(
                "kafka-feedback",
                "Kafka Feedback",
                CATEGORY_MESSAGING,
                enabled ? CONFIGURED : DISABLED,
                enabled
                        ? "Sandbox 反馈经 Kafka 消费并幂等聚合为日指标"
                        : "Kafka 反馈消费者当前已禁用",
                enabled
                        ? List.of("Inbox 去重", "日指标聚合", "DLT 兜底")
                        : List.of("Inbox 去重", "日指标聚合", "DLT 兜底"));
    }

    private Integration channelSandboxIntegration() {
        boolean enabled = Boolean.TRUE.equals(publicationProperties.workerEnabled());
        return new Integration(
                "channel-sandbox",
                "Channel Sandbox",
                CATEGORY_PUBLISHING,
                enabled ? CONFIGURED : DISABLED,
                enabled
                        ? "Channel Sandbox 发布通道已配置，由后台 Worker 异步发送"
                        : "Channel Sandbox 已配置，发布 Worker 当前已禁用",
                List.of("幂等键防重复发布", "externalPostId 回执"));
    }

    private Integration toolRegistryIntegration() {
        var snapshot = toolRegistry.definitionSnapshot();
        return new Integration(
                "tool-registry",
                "Tool Registry / MCP",
                CATEGORY_TOOLS,
                snapshot.isEmpty() ? DISABLED : CONFIGURED,
                snapshot.isEmpty()
                        ? "当前没有已发现的工具定义"
                        : "已发现 " + snapshot.size() + " 个工具定义（只读快照，不改变调用语义）",
                List.of("Java / HTTP / OpenAPI / MCP Provider SPI", "风险分级与 Tool Policy"));
    }

    private static String safeProvider(String provider, String fallback) {
        return provider == null || provider.isBlank() ? fallback : provider;
    }
}
