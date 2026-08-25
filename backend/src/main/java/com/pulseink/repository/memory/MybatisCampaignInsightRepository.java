package com.pulseink.repository.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightEvidenceRef;
import com.pulseink.domain.memory.InsightIndexStatus;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.domain.memory.InsightStatus;
import com.pulseink.service.memory.CampaignInsightRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisCampaignInsightRepository implements CampaignInsightRepository {

    /** INDEXING visibility deadline: stuck claimed rows become claimable again. */
    private static final Duration INDEXING_VISIBILITY = Duration.ofSeconds(5);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST =
            new TypeReference<>() {};

    private final CampaignInsightMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisCampaignInsightRepository(CampaignInsightMapper mapper,
                                            ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public Optional<CampaignInsight> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<CampaignInsight> findBySnapshot(long runId, String snapshotHash,
                                                    String promptVersion) {
        return Optional.ofNullable(
                        mapper.selectBySnapshot(runId, snapshotHash, promptVersion))
                .map(this::toDomain);
    }

    @Override
    public List<CampaignInsight> findByCampaign(long campaignId) {
        return mapper.selectByCampaign(campaignId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public CampaignInsight insertPending(CampaignInsight candidate) {
        var entity = toEntity(candidate);
        mapper.insert(entity);
        return require(mapper.selectById(entity.getId()));
    }

    @Override
    @Transactional
    public CampaignInsight decidePending(long id, long expectedVersion,
                                         InsightStatus targetStatus,
                                         String comment, long actorId, Instant reviewedAt) {
        if (targetStatus == InsightStatus.PENDING) {
            throw new IllegalArgumentException("decision target must be APPROVED or REJECTED");
        }
        var indexStatus = targetStatus == InsightStatus.APPROVED
                ? InsightIndexStatus.INDEX_PENDING
                : InsightIndexStatus.NOT_INDEXED;
        Instant nextAttemptAt = targetStatus == InsightStatus.APPROVED ? reviewedAt : null;
        int affected = mapper.decideCas(id, expectedVersion, targetStatus.name(),
                indexStatus.name(), actorId, comment == null ? "" : comment.strip(),
                reviewedAt, nextAttemptAt);
        if (affected != 1) {
            throw new IllegalStateException(
                    "insight " + id + " is not PENDING or changed concurrently");
        }
        return require(mapper.selectById(id));
    }

    @Override
    @Transactional
    public List<CampaignInsight> claimIndexDue(Instant now, int batchSize) {
        List<Long> ids = mapper.lockIndexClaimIds(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        Instant nextAttemptAt = now.plus(INDEXING_VISIBILITY);
        if (mapper.claimIndexByIds(ids, nextAttemptAt) != ids.size()) {
            throw new IllegalStateException("insight index claim lost rows concurrently");
        }
        return mapper.selectByIds(ids).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean markIndexed(long id, long expectedVersion, Instant indexedAt) {
        return mapper.markIndexedCas(id, expectedVersion, indexedAt) == 1;
    }

    @Override
    @Transactional
    public boolean markIndexRetry(long id, long expectedVersion,
                                  Instant nextAttemptAt, String error) {
        return mapper.markIndexRetryCas(id, expectedVersion, nextAttemptAt, truncate(error)) == 1;
    }

    @Override
    @Transactional
    public boolean markIndexFailed(long id, long expectedVersion, String error) {
        return mapper.markIndexFailedCas(id, expectedVersion, truncate(error)) == 1;
    }

    private CampaignInsightEntity toEntity(CampaignInsight insight) {
        var entity = new CampaignInsightEntity();
        entity.setId(insight.id() > 0 ? insight.id() : null);
        entity.setCampaignId(insight.campaignId());
        entity.setRunId(insight.runId());
        entity.setCategory(insight.category().name());
        entity.setTitle(insight.title());
        entity.setInsightText(insight.insightText());
        entity.setScopeType(insight.scopeType().name());
        entity.setScopeValue(insight.scopeValue());
        entity.setApplicableChannelsJson(writeJson(insight.applicableChannels().stream()
                .map(Enum::name).toList()));
        entity.setEvidenceRefsJson(writeJson(insight.evidenceRefs().stream().map(ref ->
                Map.<String, Object>of(
                        "contentVersionId", ref.contentVersionId(),
                        "publicationId", ref.publicationId(),
                        "metricFrom", ref.metricFrom().toString(),
                        "metricTo", ref.metricTo().toString())).toList()));
        entity.setLimitationsJson(writeJson(insight.limitations()));
        entity.setConfidence(insight.confidence());
        entity.setSourceSnapshotHash(insight.sourceSnapshotHash());
        entity.setPromptVersion(insight.promptVersion());
        entity.setStatus(insight.status().name());
        entity.setIndexStatus(insight.indexStatus().name());
        entity.setIndexAttempts(insight.indexAttempts());
        entity.setNextIndexAttemptAt(insight.nextIndexAttemptAt());
        entity.setLastIndexError(insight.lastIndexError());
        entity.setCreatedBy(insight.createdBy());
        entity.setReviewedBy(insight.reviewedBy());
        entity.setReviewComment(insight.reviewComment());
        entity.setVersion(insight.version());
        entity.setCreatedAt(insight.createdAt());
        entity.setReviewedAt(insight.reviewedAt());
        entity.setIndexedAt(insight.indexedAt());
        return entity;
    }

    private CampaignInsight toDomain(CampaignInsightEntity entity) {
        var evidence = readJson(entity.getEvidenceRefsJson(), MAP_LIST).stream()
                .map(this::toEvidenceRef).toList();
        return new CampaignInsight(
                entity.getId(),
                entity.getCampaignId(),
                entity.getRunId(),
                InsightCategory.valueOf(entity.getCategory()),
                entity.getTitle(),
                entity.getInsightText(),
                InsightScopeType.valueOf(entity.getScopeType()),
                entity.getScopeValue() == null ? "" : entity.getScopeValue(),
                readJson(entity.getApplicableChannelsJson(), STRING_LIST).stream()
                        .map(this::toChannel).toList(),
                evidence,
                entity.getConfidence(),
                readJson(entity.getLimitationsJson(), STRING_LIST),
                entity.getSourceSnapshotHash(),
                entity.getPromptVersion(),
                InsightStatus.valueOf(entity.getStatus()),
                InsightIndexStatus.valueOf(entity.getIndexStatus()),
                entity.getIndexAttempts() == null ? 0 : entity.getIndexAttempts(),
                entity.getNextIndexAttemptAt(),
                entity.getLastIndexError(),
                entity.getCreatedBy(),
                entity.getReviewedBy(),
                entity.getReviewComment(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                entity.getCreatedAt(),
                entity.getReviewedAt(),
                entity.getIndexedAt());
    }

    private InsightEvidenceRef toEvidenceRef(Map<String, Object> json) {
        return new InsightEvidenceRef(
                ((Number) json.get("contentVersionId")).longValue(),
                ((Number) json.get("publicationId")).longValue(),
                LocalDate.parse(String.valueOf(json.get("metricFrom"))),
                LocalDate.parse(String.valueOf(json.get("metricTo"))));
    }

    private CampaignChannel toChannel(String value) {
        try {
            return CampaignChannel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "insight stored an unknown channel value: " + value, ex);
        }
    }

    private CampaignInsight require(CampaignInsightEntity entity) {
        if (entity == null) {
            throw new IllegalStateException("insight row was not readable");
        }
        return toDomain(entity);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("insight cannot be serialized", exception);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored insight JSON is invalid", exception);
        }
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1_024 ? error : error.substring(0, 1_024);
    }
}
