package com.pulseink.config;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.service.model.ChatWithModelService;
import com.pulseink.service.model.ChatWithModelUseCase;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.OpenAiCompatibleModelAdapter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfiguration {

    @Bean("primaryModelPort")
    @ConditionalOnProperty(
            name = "pulseink.model.provider",
            havingValue = "fake",
            matchIfMissing = true)
    AgentModelPort fakeModelAdapter() {
        return new FakeModelAdapter();
    }

    @Bean("arkChatModel")
    @ConditionalOnProperty(
            name = "pulseink.model.provider",
            havingValue = "ark")
    OpenAiChatModel arkChatModel(ModelProperties properties) {
        return openAiChatModel(
                requireProvider(
                        properties.ark(),
                        "ARK",
                        "ark"),
                properties.requestTimeout());
    }

    @Bean("primaryModelPort")
    @ConditionalOnProperty(
            name = "pulseink.model.provider",
            havingValue = "ark")
    AgentModelPort arkModelAdapter(
            @Qualifier("arkChatModel") OpenAiChatModel chatModel,
            ModelProperties properties) {
        return new OpenAiCompatibleModelAdapter(
                chatModel, chatModel,
                "ark",
                requireProvider(properties.ark(), "ARK", "ark").model());
    }

    @Bean("zhipuChatModel")
    @ConditionalOnProperty(
            name = "pulseink.model.provider",
            havingValue = "zhipu")
    OpenAiChatModel zhipuChatModel(ModelProperties properties) {
        return openAiChatModel(
                requireProvider(
                        properties.zhipu(),
                        "ZHIPU",
                        "zhipu"),
                properties.requestTimeout());
    }

    @Bean("primaryModelPort")
    @ConditionalOnProperty(
            name = "pulseink.model.provider",
            havingValue = "zhipu")
    AgentModelPort zhipuModelAdapter(
            @Qualifier("zhipuChatModel") OpenAiChatModel chatModel,
            ModelProperties properties) {
        return new OpenAiCompatibleModelAdapter(
                chatModel, chatModel,
                "zhipu",
                requireProvider(properties.zhipu(), "ZHIPU", "zhipu").model());
    }

    @Bean
    @Conditional(FallbackProviderConfiguredCondition.class)
    AgentModelPort fallbackModelPort(ModelProperties properties) {
        String fallback = properties.fallbackProvider();
        if (fallback.equals(properties.provider())) {
            throw new IllegalStateException(
                    "fallback provider must differ from primary provider");
        }
        return switch (fallback) {
            case "fake" -> new FakeModelAdapter();
            case "ark" -> arkPort(properties);
            case "zhipu" -> zhipuPort(properties);
            default -> throw new IllegalStateException(
                    "unknown fallback provider: " + fallback);
        };
    }

    static final class FallbackProviderConfiguredCondition
            implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(
                org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment()
                    .getProperty("pulseink.model.fallback-provider");
            return value != null && !value.isBlank();
        }
    }

    @Bean
    ChatWithModelUseCase chatWithModelUseCase(
            @Qualifier("primaryModelPort") AgentModelPort modelPort) {
        return new ChatWithModelService(modelPort);
    }

    private static OpenAiChatModel openAiChatModel(
            ModelProperties.Provider provider,
            java.time.Duration requestTimeout) {
        var options = OpenAiChatOptions.builder()
                .apiKey(provider.apiKey())
                .baseUrl(provider.baseUrl())
                .model(provider.model())
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(builder -> builder.timeout(requestTimeout))
                .build();
    }

    private static AgentModelPort arkPort(ModelProperties properties) {
        var provider = requireProvider(properties.ark(), "ARK", "ark");
        var chatModel = openAiChatModel(provider, properties.requestTimeout());
        return new OpenAiCompatibleModelAdapter(
                chatModel, chatModel, "ark", provider.model());
    }

    private static AgentModelPort zhipuPort(ModelProperties properties) {
        var provider = requireProvider(properties.zhipu(), "ZHIPU", "zhipu");
        var chatModel = openAiChatModel(provider, properties.requestTimeout());
        return new OpenAiCompatibleModelAdapter(
                chatModel, chatModel, "zhipu", provider.model());
    }

    private static ModelProperties.Provider requireProvider(
            ModelProperties.Provider provider,
            String environmentPrefix,
            String providerName) {
        if (provider == null) {
            throw new IllegalStateException(
                    environmentPrefix
                            + "_API_KEY must not be blank when provider is "
                            + providerName);
        }
        requireText(
                provider.apiKey(),
                environmentPrefix
                        + "_API_KEY must not be blank when provider is "
                        + providerName);
        requireText(
                provider.model(),
                environmentPrefix
                        + "_MODEL must not be blank when provider is "
                        + providerName);
        requireText(
                provider.baseUrl(),
                environmentPrefix
                        + "_BASE_URL must not be blank when provider is "
                        + providerName);
        return provider;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
