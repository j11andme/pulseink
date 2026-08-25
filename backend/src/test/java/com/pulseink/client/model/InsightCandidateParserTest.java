package com.pulseink.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightSourceSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InsightCandidateParserTest {

    private static final String HASH = "a".repeat(64);

    private final JacksonInsightCandidateParser parser = new JacksonInsightCandidateParser();
    private final InsightSourceSnapshot snapshot = snapshot();

    @Test
    void validCandidateParses() {
        var insight = parser.parse(validJson(), snapshot);

        assertThat(insight.schemaVersion()).isEqualTo(1);
        assertThat(insight.category())
                .isEqualTo(com.pulseink.domain.memory.InsightCategory.CHANNEL_PATTERN);
        assertThat(insight.title()).isEqualTo("社交渠道短句更有效");
        assertThat(insight.scopeType())
                .isEqualTo(com.pulseink.domain.memory.InsightScopeType.CHANNEL);
        assertThat(insight.scopeValue()).isEqualTo("SOCIAL");
        assertThat(insight.applicableChannels()).containsExactly(CampaignChannel.SOCIAL);
        assertThat(insight.evidenceRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.contentVersionId()).isEqualTo(11L);
            assertThat(ref.publicationId()).isEqualTo(21L);
        });
        assertThat(insight.confidence()).isEqualTo(0.78);
    }

    @Test
    void rejectsMarkdownFencesAndExtraText() {
        assertInvalid("```json\n" + validJson() + "\n```");
        assertInvalid("Here is the answer: " + validJson());
    }

    @Test
    void rejectsUnknownFields() {
        assertInvalid(validJson().replace("\"confidence\":0.78",
                "\"confidence\":0.78,\"hiddenReasoning\":\"secret\""));
    }

    @Test
    void rejectsWrongSchemaVersion() {
        assertInvalid(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
    }

    @Test
    void rejectsIllegalEnumsAndChannels() {
        assertInvalid(validJson().replace("CHANNEL_PATTERN", "FANCY_PATTERN"));
        assertInvalid(validJson().replace("\"scopeType\":\"CHANNEL\"",
                "\"scopeType\":\"TENANT\""));
        assertInvalid(validJson().replace("\"SOCIAL\"", "\"TIKTOK\""));
    }

    @Test
    void rejectsOutOfRangeConfidenceAndEmptyEvidence() {
        assertInvalid(validJson().replace("\"confidence\":0.78", "\"confidence\":1.5"));
        assertInvalid(validJson().replace("\"confidence\":0.78", "\"confidence\":-0.2"));
        assertInvalid(validJson().replace(
                "\"evidenceRefs\":[{\"contentVersionId\":11,\"publicationId\":21,"
                        + "\"metricFrom\":\"2026-08-06\",\"metricTo\":\"2026-08-07\"}]",
                "\"evidenceRefs\":[]"));
    }

    @Test
    void rejectsEvidenceRefsOutsideTheSnapshot() {
        assertInvalid(validJson().replace("\"contentVersionId\":11",
                "\"contentVersionId\":999"));
        assertInvalid(validJson().replace("\"publicationId\":21", "\"publicationId\":999"));
        assertInvalid(validJson().replace("\"metricFrom\":\"2026-08-06\"",
                "\"metricFrom\":\"2026-09-01\""));
        assertInvalid(validJson()
                .replace("\"metricFrom\":\"2026-08-06\"",
                        "\"metricFrom\":\"2026-07-01\"")
                .replace("\"metricTo\":\"2026-08-07\"",
                        "\"metricTo\":\"2026-09-01\""));
    }

    @Test
    void rejectsOversizedFields() {
        assertInvalid(validJson().replace("社交渠道短句更有效", "长".repeat(121)));
        assertInvalid(validJson().replace("短句形式能提升互动", "文".repeat(2_001)));
    }

    @Test
    void missingRequiredStringsMapToStableInvalidOutput() {
        assertInvalid(validJson().replace("\"title\":\"社交渠道短句更有效\",", ""));
        assertInvalid(validJson().replace("\"insightText\":\"短句形式能提升互动\",", ""));
    }

    @Test
    void evidenceVersionMustBeTheExactVersionPublishedByTheReferencedPublication() {
        assertInvalid(validJson().replace("\"contentVersionId\":11",
                "\"contentVersionId\":12"));
    }

    @Test
    void rejectsChannelScopeWithoutValueAndWorkspaceScopeWithValue() {
        assertInvalid(validJson().replace("\"scopeValue\":\"SOCIAL\"", "\"scopeValue\":\"\""));
        assertInvalid(validJson().replace("\"scopeType\":\"CHANNEL\",\"scopeValue\":\"SOCIAL\"",
                "\"scopeType\":\"WORKSPACE\",\"scopeValue\":\"SOCIAL\""));
    }

    @Test
    void workspaceScopeWithEmptyValueParses() {
        var insight = parser.parse(validJson()
                .replace("\"scopeType\":\"CHANNEL\",\"scopeValue\":\"SOCIAL\"",
                        "\"scopeType\":\"WORKSPACE\",\"scopeValue\":\"\""), snapshot);
        assertThat(insight.scopeValue()).isEmpty();
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> parser.parse(json, snapshot))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_MODEL_OUTPUT_INVALID));
    }

    private static String validJson() {
        return "{\"schemaVersion\":1,\"category\":\"CHANNEL_PATTERN\","
                + "\"title\":\"社交渠道短句更有效\",\"insightText\":\"短句形式能提升互动\","
                + "\"scopeType\":\"CHANNEL\",\"scopeValue\":\"SOCIAL\","
                + "\"applicableChannels\":[\"SOCIAL\"],"
                + "\"evidenceRefs\":[{\"contentVersionId\":11,\"publicationId\":21,"
                + "\"metricFrom\":\"2026-08-06\",\"metricTo\":\"2026-08-07\"}],"
                + "\"confidence\":0.78,\"limitations\":[\"样本窗口较短\"]}";
    }

    private static InsightSourceSnapshot snapshot() {
        return new InsightSourceSnapshot(
                2L, 1L,
                List.of(new InsightSourceSnapshot.ApprovedVersion(11L, 1,
                        Map.of("title", "T", "body", "B")),
                        new InsightSourceSnapshot.ApprovedVersion(12L, 2,
                                Map.of("title", "T2", "body", "B2"))),
                List.of(new InsightSourceSnapshot.PublishedPost(21L, 11L,
                        CampaignChannel.SOCIAL, UUID.randomUUID(),
                        Instant.parse("2026-08-07T12:00:00Z"))),
                List.of(new InsightSourceSnapshot.MetricWindow(21L,
                        LocalDate.of(2026, 8, 6), 50, 5, 2),
                        new InsightSourceSnapshot.MetricWindow(21L,
                                LocalDate.of(2026, 8, 7), 60, 6, 3)),
                HASH);
    }
}
