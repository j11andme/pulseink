package com.pulseink.client.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightEvidenceRef;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.service.memory.GeneratedInsight;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightSourceSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict parser for the InsightCandidate model protocol: no Markdown fences, no unknown
 * fields, schema version 1 only, valid enums, bounded strings, unit confidence and evidence
 * refs that must all exist inside the given source snapshot.
 */
public final class JacksonInsightCandidateParser {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final int MAX_TITLE_CODE_POINTS = 120;
    private static final int MAX_TEXT_CODE_POINTS = 2_000;
    private static final int MAX_SCOPE_VALUE_CODE_POINTS = 64;
    private static final int MAX_LIMITATIONS = 20;
    private static final int MAX_LIMITATION_CODE_POINTS = 500;

    private final ObjectMapper objectMapper;

    public JacksonInsightCandidateParser() {
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public GeneratedInsight parse(String json, InsightSourceSnapshot source) {
        CandidateDto dto;
        try {
            dto = objectMapper.readValue(json, CandidateDto.class);
        } catch (JsonProcessingException malformed) {
            throw invalid("insight output is not a single strict JSON object: "
                    + malformed.getOriginalMessage());
        }
        if (dto == null) {
            throw invalid("insight output must be a single JSON object");
        }
        if (dto.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw invalid("unsupported insight schema version " + dto.schemaVersion());
        }
        InsightCategory category = enumValue(InsightCategory.class,
                dto.category(), "category");
        String title = boundedText(dto.title(), "title", MAX_TITLE_CODE_POINTS, true);
        String insightText = boundedText(dto.insightText(), "insightText",
                MAX_TEXT_CODE_POINTS, true);
        InsightScopeType scopeType = enumValue(InsightScopeType.class,
                dto.scopeType(), "scopeType");
        String scopeValue = dto.scopeValue() == null ? "" : dto.scopeValue();
        if (scopeType == InsightScopeType.CHANNEL) {
            scopeValue = boundedText(scopeValue, "scopeValue",
                    MAX_SCOPE_VALUE_CODE_POINTS, true);
        } else if (!scopeValue.isBlank()) {
            throw invalid("WORKSPACE scope must not carry a scopeValue");
        } else {
            scopeValue = "";
        }

        var channels = new ArrayList<CampaignChannel>();
        if (dto.applicableChannels() == null) {
            throw invalid("applicableChannels must contain 1 to 3 distinct channels");
        }
        for (String channel : dto.applicableChannels()) {
            channels.add(enumValue(CampaignChannel.class, channel, "applicableChannels"));
        }
        if (channels.isEmpty() || channels.size() > 3
                || new java.util.HashSet<>(channels).size() != channels.size()) {
            throw invalid("applicableChannels must contain 1 to 3 distinct channels");
        }

        var evidenceNode = dto.evidenceRefs();
        if (evidenceNode == null || evidenceNode.isEmpty()) {
            throw invalid("evidenceRefs must be a non-empty array");
        }
        if (evidenceNode.size() > MAX_LIMITATIONS) {
            throw invalid("evidenceRefs exceeds the limit of " + MAX_LIMITATIONS);
        }
        var evidence = new ArrayList<InsightEvidenceRef>();
        for (EvidenceDto ref : evidenceNode) {
            evidence.add(evidenceRef(ref, source));
        }

        Double confidence = dto.confidence();
        if (confidence == null || confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
            throw invalid("confidence must be within [0, 1]");
        }

        var limitations = new ArrayList<String>();
        if (dto.limitations() != null) {
            for (String limitation : dto.limitations()) {
                limitations.add(boundedText(limitation, "limitation",
                        MAX_LIMITATION_CODE_POINTS, true));
            }
        }
        if (limitations.size() > MAX_LIMITATIONS) {
            throw invalid("limitations exceeds the limit of " + MAX_LIMITATIONS);
        }

        return new GeneratedInsight(SUPPORTED_SCHEMA_VERSION, category, title, insightText,
                scopeType, scopeValue, List.copyOf(channels), List.copyOf(evidence),
                confidence, List.copyOf(limitations));
    }

    private InsightEvidenceRef evidenceRef(EvidenceDto ref, InsightSourceSnapshot source) {
        if (ref == null) {
            throw invalid("evidence refs must be objects");
        }
        long contentVersionId = ref.contentVersionId();
        long publicationId = ref.publicationId();
        String from = ref.metricFrom() == null ? "" : ref.metricFrom();
        String to = ref.metricTo() == null ? "" : ref.metricTo();
        if (contentVersionId <= 0 || publicationId <= 0
                || from.isBlank() || to.isBlank()) {
            throw invalid("evidence refs must carry valid ids and a metric window");
        }
        LocalDate metricFrom;
        LocalDate metricTo;
        try {
            metricFrom = LocalDate.parse(from);
            metricTo = LocalDate.parse(to);
        } catch (RuntimeException malformed) {
            throw invalid("evidence metric window must be a valid date range");
        }
        if (!source.containsVersion(contentVersionId)) {
            throw invalid("evidence references an unknown contentVersionId "
                    + contentVersionId);
        }
        if (!source.containsPublication(publicationId)) {
            throw invalid("evidence references an unknown publicationId " + publicationId);
        }
        if (!source.containsPublishedVersion(publicationId, contentVersionId)) {
            throw invalid("evidence contentVersionId was not published by publicationId "
                    + publicationId);
        }
        if (!source.containsMetricWindow(publicationId, metricFrom, metricTo)) {
            throw invalid("evidence metric window has no facts in the snapshot");
        }
        return new InsightEvidenceRef(contentVersionId, publicationId, metricFrom, metricTo);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw invalid("unknown " + field + " value: " + value);
        }
    }

    private static String boundedText(String value, String field,
                                      int maxCodePoints, boolean required) {
        if (value == null) {
            throw invalid(field + " must not be null");
        }
        String normalized = value.strip();
        if (required && normalized.isEmpty()) {
            throw invalid(field + " must not be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw invalid(field + " exceeds " + maxCodePoints + " code points");
        }
        return normalized;
    }

    private static InsightException invalid(String message) {
        return new InsightException(InsightErrorCode.INSIGHT_MODEL_OUTPUT_INVALID, message);
    }

    /** Strict wire DTO: unknown fields are rejected by the configured ObjectMapper. */
    private record CandidateDto(
            int schemaVersion,
            String category,
            String title,
            String insightText,
            String scopeType,
            String scopeValue,
            List<String> applicableChannels,
            List<EvidenceDto> evidenceRefs,
            Double confidence,
            List<String> limitations) {
    }

    private record EvidenceDto(
            long contentVersionId,
            long publicationId,
            String metricFrom,
            String metricTo) {
    }
}
