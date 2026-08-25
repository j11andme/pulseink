package com.pulseink.repository.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.domain.publication.Publication;
import com.pulseink.domain.publication.PublishReceipt;
import com.pulseink.service.publishing.PublicationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisPublicationRepository implements PublicationRepository {

    /**
     * SENDING visibility deadline: a claimed row older than this window without a terminal
     * outcome is considered lost work and becomes claimable again.
     */
    private static final Duration SENDING_VISIBILITY = Duration.ofSeconds(5);

    private final PublicationMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisPublicationRepository(PublicationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional
    public Publication createOrGet(Publication pending) {
        var entity = PublicationMappings.toEntity(pending);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            var existing = mapper.selectByVersionAndChannel(
                    pending.contentVersionId(), pending.channel().name());
            if (existing == null) {
                throw new IllegalStateException(
                        "publication dedupe conflict without an existing row", duplicate);
            }
            return PublicationMappings.toDomain(existing);
        }
        return require(mapper.selectById(entity.getId()));
    }

    @Override
    public Optional<Publication> findById(long publicationId) {
        return Optional.ofNullable(mapper.selectById(publicationId))
                .map(PublicationMappings::toDomain);
    }

    @Override
    public List<Publication> findByRunId(long runId) {
        return mapper.selectByRunId(runId).stream()
                .map(PublicationMappings::toDomain).toList();
    }

    @Override
    @Transactional
    public List<Publication> claimDue(Instant now, int batchSize) {
        List<Long> claimedIds = mapper.lockClaimIds(now, batchSize);
        if (claimedIds.isEmpty()) {
            return List.of();
        }
        Instant nextAttemptAt = now.plus(SENDING_VISIBILITY);
        if (mapper.claimByIds(claimedIds, nextAttemptAt) != claimedIds.size()) {
            throw new IllegalStateException("publication claim lost rows concurrently");
        }
        return mapper.selectByIds(claimedIds).stream()
                .map(PublicationMappings::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean claim(long publicationId, long expectedVersion) {
        return mapper.claimById(publicationId, expectedVersion) == 1;
    }

    @Override
    @Transactional
    public boolean markPublished(long publicationId, long expectedVersion, PublishReceipt receipt) {
        int affected = mapper.markPublished(publicationId, expectedVersion,
                receipt.externalPostId().toString(), writeJson(receipt), receipt.publishedAt());
        return affected == 1;
    }

    @Override
    @Transactional
    public boolean markRetryWait(long publicationId, long expectedVersion,
                                 Instant nextAttemptAt, String failureCode, String failureMessage) {
        return mapper.markRetryWait(publicationId, expectedVersion, nextAttemptAt,
                failureCode, failureMessage) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(long publicationId, long expectedVersion,
                              String failureCode, String failureMessage) {
        return mapper.markFailed(publicationId, expectedVersion, failureCode, failureMessage) == 1;
    }

    private Publication require(PublicationEntity entity) {
        if (entity == null) {
            throw new IllegalStateException("publication insert was not readable");
        }
        return PublicationMappings.toDomain(entity);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("publish receipt cannot be serialized", exception);
        }
    }
}
