package com.pulseink.config;

import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.config.properties.ChannelProperties;
import com.pulseink.config.properties.EmbeddingProperties;
import com.pulseink.config.properties.FeedbackProperties;
import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.config.properties.PublicationProperties;
import com.pulseink.config.properties.RunLeaseProperties;
import com.pulseink.service.integration.IntegrationQueryService;
import com.pulseink.service.integration.QueryIntegrationUseCase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Product-facing integration status wiring, isolated from the core application services. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        ModelProperties.class,
        EmbeddingProperties.class,
        KnowledgeProperties.class,
        MemoryProperties.class,
        RunLeaseProperties.class,
        ChannelProperties.class,
        PublicationProperties.class,
        FeedbackProperties.class
})
public class IntegrationConfiguration {

    @Bean
    QueryIntegrationUseCase queryIntegrationUseCase(
            ModelProperties modelProperties,
            EmbeddingProperties embeddingProperties,
            KnowledgeProperties knowledgeProperties,
            MemoryProperties memoryProperties,
            RunLeaseProperties runLeaseProperties,
            ChannelProperties channelProperties,
            PublicationProperties publicationProperties,
            FeedbackProperties feedbackProperties,
            ToolRegistry toolRegistry) {
        return new IntegrationQueryService(
                modelProperties,
                embeddingProperties,
                knowledgeProperties,
                memoryProperties,
                runLeaseProperties,
                channelProperties,
                publicationProperties,
                feedbackProperties,
                toolRegistry);
    }
}
