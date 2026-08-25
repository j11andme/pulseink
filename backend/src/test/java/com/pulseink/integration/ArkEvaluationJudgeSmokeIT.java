package com.pulseink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.service.evaluation.LlmJudgeScorer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Two-call real-provider compatibility smoke for the anonymous AB/BA evaluation judge. */
@Tag("real-model")
@EnabledIfSystemProperty(named = "pulseink.real-model-smoke", matches = "true")
class ArkEvaluationJudgeSmokeIT {

    @Test
    void realArkReturnsTheStrictJudgeProtocol() {
        var application = new SpringApplication(
                ArkOrchestratedSmokeIT.MinimalSmokeConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration"));
        try (var context = application.run()) {
            String provider = context.getEnvironment()
                    .getProperty("pulseink.model.provider", "");
            if (!"ark".equals(provider)) {
                throw new org.opentest4j.TestAbortedException(
                        "BLOCKED_REAL_PROVIDER: PULSEINK_MODEL_PROVIDER=" + provider);
            }
            AgentModelPort primary = context.getBean("primaryModelPort", AgentModelPort.class);
            var buffers = new ConcurrentHashMap<String, StringBuffer>();
            var shapes = Collections.synchronizedList(new ArrayList<String>());
            AgentModelPort observed = (request, consumer) -> primary.stream(request, event -> {
                if (event instanceof ModelStreamEvent.ContentDelta delta) {
                    buffers.computeIfAbsent(request.requestId(), ignored -> new StringBuffer())
                            .append(delta.content());
                }
                if (event instanceof ModelStreamEvent.Completed) {
                    shapes.add(responseShape(
                            buffers.getOrDefault(request.requestId(), new StringBuffer())));
                }
                consumer.accept(event);
            });
            var judge = new LlmJudgeScorer(
                    observed,
                    new ObjectMapper().findAndRegisterModules(),
                    Duration.ofSeconds(180));

            var score = judge.scoreBothOrders(
                    "面向 Java 开发者的 PulseInk 活动草稿 A。",
                    "面向 Java 开发者的 PulseInk 活动草稿 B。",
                    "content-v1");

            assertThat(score.executed()).isTrue();
            assertThat(score.parseFailure())
                    .as("strict judge response shapes=%s", shapes)
                    .isFalse();
            assertThat(score.orders()).containsExactly("AB", "BA");
            assertThat(score.judgeModel()).isNotBlank();
            assertThat(score.promptVersion()).isEqualTo("judge-v2-explainable");
        } catch (org.opentest4j.TestAbortedException blocked) {
            throw blocked;
        } catch (RuntimeException providerFailure) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED_REAL_PROVIDER: "
                            + providerFailure.getClass().getSimpleName(),
                    providerFailure);
        }
    }

    private static String responseShape(CharSequence response) {
        String text = response.toString().trim();
        boolean fence = text.startsWith("```") || text.endsWith("```");
        try {
            var root = new ObjectMapper().readTree(text);
            var fields = new java.util.TreeMap<String, String>();
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(entry ->
                        fields.put(entry.getKey(), entry.getValue().getNodeType().name()));
            }
            return "length=" + text.length()
                    + ",fence=" + fence
                    + ",node=" + (root == null ? "null" : root.getNodeType())
                    + ",fields=" + fields;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
            return "length=" + text.length()
                    + ",fence=" + fence
                    + ",node=INVALID_JSON";
        }
    }
}
