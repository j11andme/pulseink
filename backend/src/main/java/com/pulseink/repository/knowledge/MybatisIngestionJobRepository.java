package com.pulseink.repository.knowledge;

import com.pulseink.domain.knowledge.IngestionJob;
import com.pulseink.domain.knowledge.IngestionJobStatus;
import com.pulseink.service.knowledge.IngestionJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisIngestionJobRepository implements IngestionJobRepository {

    private final IngestionJobMapper mapper;

    public MybatisIngestionJobRepository(IngestionJobMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public IngestionJob insert(IngestionJob job) {
        var entity = toEntity(job);
        mapper.insert(entity);
        var persisted = mapper.selectById(entity.getId());
        if (persisted == null) {
            throw new IllegalStateException(
                    "ingestion job insert did not produce a readable row");
        }
        return toDomain(persisted);
    }

    @Override
    public Optional<IngestionJob> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<IngestionJob> findByDocumentId(long documentId) {
        return Optional.ofNullable(mapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<IngestionJobEntity>()
                                .eq("document_id", documentId)))
                .map(this::toDomain);
    }

    @Override
    public void update(IngestionJob job) {
        int affected = mapper.updateStateCas(
                job.id(),
                job.status().name(),
                job.failureCode(),
                job.attempt(),
                job.startedAt(),
                job.completedAt(),
                job.version());
        if (affected != 1) {
            throw new IllegalStateException(
                    "stale ingestion job update for id " + job.id()
                            + ": expected version " + job.version());
        }
    }

    @Override
    public void startProcessing(long id, Instant startedAt) {
        var job = requireJob(id);
        job.startProcessing(startedAt);
        update(job);
    }

    @Override
    public void markSucceeded(long id, Instant completedAt) {
        var job = requireJob(id);
        job.markSucceeded(completedAt);
        update(job);
    }

    @Override
    public void markFailed(long id, String failureCode, Instant completedAt) {
        var job = requireJob(id);
        job.markFailed(failureCode, completedAt);
        update(job);
    }

    @Override
    public void retry(long id) {
        var job = requireJob(id);
        job.retry();
        update(job);
    }

    @Override
    public List<IngestionJob> findRecoverable(int limit, Duration staleTimeout) {
        var staleBefore = Instant.now().minus(staleTimeout);
        var entities = mapper.findRecoverable(staleBefore, limit);
        var jobs = new ArrayList<IngestionJob>();
        for (var entity : entities) {
            jobs.add(toDomain(entity));
        }
        return List.copyOf(jobs);
    }

    @Override
    public List<IngestionJob> findPending(int limit) {
        var entities = mapper.findPending(limit);
        var jobs = new ArrayList<IngestionJob>();
        for (var entity : entities) {
            jobs.add(toDomain(entity));
        }
        return List.copyOf(jobs);
    }

    private IngestionJob requireJob(long id) {
        return findById(id).orElseThrow(() ->
                new IllegalArgumentException("ingestion job " + id + " was not found"));
    }

    private IngestionJobEntity toEntity(IngestionJob job) {
        var entity = new IngestionJobEntity();
        entity.setId(job.id());
        entity.setJobId(job.jobId());
        entity.setDocumentId(job.documentId());
        entity.setStatus(job.status().name());
        entity.setAttempt(job.attempt());
        entity.setFailureCode(job.failureCode());
        entity.setStartedAt(job.startedAt());
        entity.setCompletedAt(job.completedAt());
        entity.setVersion(job.version());
        return entity;
    }

    private IngestionJob toDomain(IngestionJobEntity entity) {
        return IngestionJob.materialize(
                entity.getId(),
                entity.getJobId(),
                entity.getDocumentId(),
                enumValue(entity.getStatus(), IngestionJobStatus.class, "status"),
                entity.getAttempt() == null ? 0 : entity.getAttempt(),
                entity.getFailureCode(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("stored unknown " + label + " value: " + value, ex);
        }
    }
}
