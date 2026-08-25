package com.pulseink.controller.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.config.ModelProperties;
import com.pulseink.config.properties.ChannelProperties;
import com.pulseink.config.properties.EmbeddingProperties;
import com.pulseink.config.properties.FeedbackProperties;
import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.config.properties.PublicationProperties;
import com.pulseink.config.properties.RunLeaseProperties;
import com.pulseink.service.integration.IntegrationQueryService;
import com.pulseink.service.integration.QueryIntegrationUseCase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IntegrationControllerTest {

    @Test
    void integrationStatusMatchesConfigurationAndNeverLeaksSecrets() throws Exception {
        var model = new ModelProperties(
                "fake",
                "",
                null,
                new ModelProperties.Provider("ark-secret", "http://internal-ark/api/v3", "ark-model"),
                new ModelProperties.Provider("zhipu-secret", "http://internal-zhipu/api/v4", "zhipu-model"));
        var embedding = new EmbeddingProperties(
                "fake", null, "embedding-secret", "embedding-model", 64, "dimensions", 16,
                Duration.ofSeconds(30));
        var knowledge = knowledgeProperties();
        var memory = new MemoryProperties(
                null, 3, 10, 12_000, Duration.ofMinutes(30), true,
                Duration.ofSeconds(2), 3);
        var lease = new RunLeaseProperties(
                true, "test-owner", Duration.ofSeconds(30), Duration.ofSeconds(10));
        var channel = new ChannelProperties(
                "http://sandbox-internal:8090/channel-api/v1",
                Duration.ofSeconds(3), Duration.ofSeconds(10));
        var publication = new PublicationProperties(
                false, Duration.ofSeconds(1), 20, 3, Duration.ofSeconds(5));
        var feedback = new FeedbackProperties(
                "pulseink.feedback.raw.v1", "pulseink.feedback.raw.v1-dlt",
                "pulseink-feedback-v1", 3, false);
        var registry = new ToolRegistry(List.of(
                provider("builtin", List.of(ToolDefinition.of(
                        "builtin", "publish_content",
                        "Publish through the channel sandbox after approval",
                        ToolDefinition.Schema.empty(), ToolRisk.EXTERNAL_SIDE_EFFECT))),
                provider("mcp.docs", List.of(ToolDefinition.of(
                        "mcp.docs", "search",
                        "Read-only document search",
                        ToolDefinition.Schema.empty(), ToolRisk.READ)))));

        QueryIntegrationUseCase useCase = new IntegrationQueryService(
                model, embedding, knowledge, memory, lease, channel, publication, feedback,
                registry);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new IntegrationController(useCase))
                .build();

        var response = mockMvc.perform(get("/api/integrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrations.length()").value(7))
                .andExpect(jsonPath("$.integrations[?(@.id=='model')].status").value("CONFIGURED"))
                .andExpect(jsonPath("$.integrations[?(@.id=='model')].summary")
                        .value(hasItem(containsString("fake"))))
                .andExpect(jsonPath("$.integrations[?(@.id=='model')].summary")
                        .value(hasItem(containsString("本地演示"))))
                .andExpect(jsonPath("$.integrations[?(@.id=='embedding')].status").value("CONFIGURED"))
                .andExpect(jsonPath("$.integrations[?(@.id=='kafka-feedback')].status")
                        .value("DISABLED"))
                .andExpect(jsonPath("$.integrations[?(@.id=='channel-sandbox')].status")
                        .value("DISABLED"))
                .andExpect(jsonPath("$.integrations[?(@.id=='tool-registry')].status")
                        .value("CONFIGURED"))
                .andExpect(jsonPath("$.tools.length()").value(2))
                .andExpect(jsonPath("$.tools[0].qualifiedName").value("builtin.publish_content"))
                .andExpect(jsonPath("$.tools[0].risk").value("EXTERNAL_SIDE_EFFECT"))
                .andExpect(jsonPath("$.tools[1].qualifiedName").value("mcp.docs.search"))
                .andReturn();

        var body = response.getResponse().getContentAsString().toLowerCase();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("ark-secret")
                .doesNotContain("zhipu-secret")
                .doesNotContain("embedding-secret")
                .doesNotContain("http://")
                .doesNotContain("apikey")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("secret");
    }

    private static KnowledgeProperties knowledgeProperties() {
        return new KnowledgeProperties(
                java.nio.file.Path.of("./target/test-knowledge"),
                10L * 1024 * 1024,
                2_000_000L,
                1000,
                120,
                2000,
                "pulseink-knowledge-active",
                20,
                60,
                500,
                Duration.ofMinutes(10));
    }

    private static ToolProvider provider(String namespace, List<ToolDefinition> definitions) {
        return new ToolProvider() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public List<ToolDefinition> discover() {
                return definitions;
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("ok");
            }
        };
    }
}
