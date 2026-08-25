package com.pulseink.service.memory;

import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.campaign.RunJournal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default memory orchestration: MySQL is the authority for working memory and episodic facts,
 * Redis only caches the working memory and ES only serves approved insight search. Cache or
 * search outages degrade to MySQL/empty results and never fail a run.
 */
public final class DefaultMemoryPort implements MemoryPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryPort.class);
    private static final int ARTIFACT_SUMMARY_CODE_POINTS = 500;

    private final RunJournal journal;
    private final MemorySourceRepository sourceRepository;
    private final InsightSearchStore searchStore;
    private final RunWorkingMemoryCache cache;
    private final int approvedTopK;

    public DefaultMemoryPort(RunJournal journal,
                             MemorySourceRepository sourceRepository,
                             InsightSearchStore searchStore,
                             RunWorkingMemoryCache cache,
                             int approvedTopK) {
        this.journal = Objects.requireNonNull(journal);
        this.sourceRepository = Objects.requireNonNull(sourceRepository);
        this.searchStore = Objects.requireNonNull(searchStore);
        this.cache = Objects.requireNonNull(cache);
        this.approvedTopK = Math.max(1, approvedTopK);
    }

    @Override
    public WorkingMemoryResult loadRunWorkingMemory(long runId) {
        var checkpoint = latestCheckpoint(runId).orElse(null);
        var cached = loadCached(runId);
        if (cached != null && cacheIsCurrent(cached, checkpoint)) {
            return new WorkingMemoryResult(cached, true);
        }
        RunWorkingMemory rebuilt = checkpoint == null
                ? RunWorkingMemory.empty(runId)
                : fromCheckpoint(checkpoint);
        try {
            cache.put(runId, rebuilt);
        } catch (RuntimeException cacheFailure) {
            log.warn("RUN_MEMORY_CACHE_WRITE_FAILED runId={}", runId);
        }
        return new WorkingMemoryResult(rebuilt, false);
    }

    private RunWorkingMemory loadCached(long runId) {
        try {
            return cache.load(runId).orElse(null);
        } catch (RuntimeException cacheDown) {
            log.warn("RUN_MEMORY_CACHE_READ_FAILED runId={}", runId);
            return null;
        }
    }

    private static boolean cacheIsCurrent(RunWorkingMemory cached,
                                          com.pulseink.agent.checkpoint.RunCheckpoint checkpoint) {
        if (cached.schemaVersion() != RunWorkingMemory.SUPPORTED_SCHEMA_VERSION) {
            return false;
        }
        if (checkpoint == null) {
            // No checkpoint exists yet: only the rebuilt empty projection is a valid hit.
            return cached.lastPersistedEventSequence() == 0
                    && cached.validArtifacts().isEmpty();
        }
        return cached.lastPersistedEventSequence()
                        == checkpoint.lastPersistedEventSequence()
                && cached.lastCompletedRound() == checkpoint.lastCompletedRound()
                && cached.checkpointType().equals(checkpoint.checkpointType())
                && cached.createdAt().equals(checkpoint.createdAt());
    }

    @Override
    public CampaignEpisodicMemory loadCampaignEpisode(long runId) {
        try {
            return sourceRepository.loadEpisode(runId);
        } catch (RuntimeException failure) {
            log.warn("RUN_EPISODIC_LOAD_FAILED runId={}", runId);
            return CampaignEpisodicMemory.empty(runId);
        }
    }

    @Override
    public List<ApprovedInsightHit> searchApprovedInsights(String query,
                                                           CampaignChannel channel,
                                                           int topK) {
        try {
            return searchStore.search(query, channel, Math.min(Math.max(1, topK), approvedTopK));
        } catch (RuntimeException searchFailure) {
            log.warn("APPROVED_INSIGHT_SEARCH_UNAVAILABLE runContinuesWithoutInsights");
            return List.of();
        }
    }

    private Optional<com.pulseink.agent.checkpoint.RunCheckpoint> latestCheckpoint(long runId) {
        try {
            return journal.latestCheckpoint(runId);
        } catch (RuntimeException corruption) {
            log.warn("RUN_CHECKPOINT_UNREADABLE runId={}", runId);
            return Optional.empty();
        }
    }

    private static RunWorkingMemory fromCheckpoint(
            com.pulseink.agent.checkpoint.RunCheckpoint checkpoint) {
        var summaries = checkpoint.artifacts().stream()
                .filter(artifact -> artifact.status() == ArtifactStatus.VALID)
                .map(DefaultMemoryPort::summarize)
                .toList();
        return new RunWorkingMemory(
                checkpoint.runId(),
                checkpoint.checkpointType(),
                checkpoint.schemaVersion(),
                checkpoint.lastCompletedRound(),
                checkpoint.lastPersistedEventSequence(),
                checkpoint.createdAt(),
                summaries,
                checkpoint.budgetSnapshot());
    }

    private static RunWorkingMemory.ArtifactSummary summarize(
            com.pulseink.agent.artifact.AgentArtifact artifact) {
        String raw = String.valueOf(artifact.content());
        int codePoints = raw.codePointCount(0, raw.length());
        String summary = codePoints <= ARTIFACT_SUMMARY_CODE_POINTS
                ? raw
                : raw.substring(0, raw.offsetByCodePoints(
                        0, ARTIFACT_SUMMARY_CODE_POINTS)) + "...";
        return new RunWorkingMemory.ArtifactSummary(
                artifact.artifactId(),
                artifact.taskId(),
                artifact.type(),
                artifact.artifactVersion(),
                artifact.status(),
                summary);
    }
}
