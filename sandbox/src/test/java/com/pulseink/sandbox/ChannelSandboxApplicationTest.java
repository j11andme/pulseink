package com.pulseink.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.annotation.AnnotatedElementUtils;

class ChannelSandboxApplicationTest {

    @Test
    void exposesAStandaloneSpringBootEntryPoint() {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                        ChannelSandboxApplication.class, SpringBootApplication.class))
                .isTrue();
    }

    /**
     * Contract verification against the shared Kafka Feedback Event V1 fixture: the same JSON
     * file is parsed by the backend consumer and the sandbox outbox, keeping both applications
     * contract-compatible without sharing source modules.
     */
    @Test
    void parsesTheSharedFeedbackEventV1Fixture() throws Exception {
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var json = objectMapper.readTree(getClass()
                .getResourceAsStream("/fixtures/feedback-event-v1.json"));

        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("eventType").asText()).isEqualTo("CHANNEL_METRICS_RECORDED");
        assertThat(UUID.fromString(json.get("eventId").asText())).isNotNull();
        assertThat(Instant.parse(json.get("occurredAt").asText())).isNotNull();
        assertThat(LocalDate.parse(json.get("metricDate").asText()))
                .isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(json.get("deltas").get("views").asLong()).isEqualTo(100);
        assertThat(json.get("deltas").get("clicks").asLong()).isEqualTo(12);
        assertThat(json.get("deltas").get("likes").asLong()).isEqualTo(4);
        assertThat(json.get("externalPostId").asText()).isNotBlank();
    }
}
