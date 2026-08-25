package com.pulseink.repository.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.service.campaign.RunEvent;
import com.pulseink.service.campaign.RunEventType;
import com.pulseink.service.campaign.RunJournal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL-backed journal. Every append locks the owning {@code campaign_run} row in the same
 * transaction before computing the next monotonic sequence; checkpoints and their
 * ARTIFACT_CREATED event are written in one short transaction. Unique-index conflicts are never
 * swallowed.
 */
@Repository
public class MybatisRunJournal implements RunJournal {

    private final RunEventMapper eventMapper;
    private final RunCheckpointMapper checkpointMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MybatisRunJournal(RunEventMapper eventMapper,
                             RunCheckpointMapper checkpointMapper,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.eventMapper = Objects.requireNonNull(eventMapper);
        this.checkpointMapper = Objects.requireNonNull(checkpointMapper);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional
    public RunEvent appendEvent(long runId, RunEventType type,
                                Map<String, Object> payload) {
        lockRunRow(runId);
        long sequence = nextSequence(runId);
        return insertEvent(runId, sequence, type, payload);
    }

    @Override
    @Transactional
    public RunEvent saveCheckpointAndAppendEvent(
            RunCheckpoint checkpoint,
            RunEventType type,
            Map<String, Object> payload) {
        lockRunRow(checkpoint.runId());
        long sequence = nextSequence(checkpoint.runId());
        var persistedCheckpoint = RunCheckpoint.of(
                checkpoint.runId(),
                checkpoint.checkpointType(),
                checkpoint.artifacts(),
                checkpoint.budgetSnapshot(),
                checkpoint.lastCompletedRound(),
                sequence,
                checkpoint.createdAt());
        var entity = new RunCheckpointEntity();
        entity.setRunId(persistedCheckpoint.runId());
        entity.setCheckpointType(persistedCheckpoint.checkpointType());
        entity.setCheckpointDataJson(toJsonMap(persistedCheckpoint));
        entity.setSchemaVersion(persistedCheckpoint.schemaVersion());
        checkpointMapper.insert(entity);

        return insertEvent(checkpoint.runId(), sequence, type, payload);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunCheckpoint> latestCheckpoint(long runId) {
        var entity = checkpointMapper.latest(runId);
        if (entity == null) {
            return Optional.empty();
        }
        if (entity.getSchemaVersion() == null
                || entity.getSchemaVersion() != RunCheckpoint.SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "unsupported checkpoint schema version: " + entity.getSchemaVersion());
        }
        return Optional.of(fromJsonMap(entity.getCheckpointDataJson()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunEvent> findEventsAfter(long runId, long lastSequence) {
        var entities = eventMapper.findAfter(runId, lastSequence);
        var result = new java.util.ArrayList<RunEvent>();
        for (var entity : entities) {
            result.add(toEvent(entity));
        }
        return List.copyOf(result);
    }

    private void lockRunRow(long runId) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM campaign_run WHERE id = ? FOR UPDATE",
                Long.class,
                runId);
    }

    private long nextSequence(long runId) {
        Long max = eventMapper.maxSequence(runId);
        return max == null ? 1L : max + 1L;
    }

    private RunEvent insertEvent(long runId, long sequence, RunEventType type,
                                 Map<String, Object> payload) {
        var enriched = new LinkedHashMap<String, Object>();
        enriched.put("eventVersion", RunEvent.EVENT_VERSION);
        if (payload != null) {
            enriched.putAll(payload);
        }
        var entity = new RunEventEntity();
        entity.setRunId(runId);
        entity.setSequenceNo(sequence);
        entity.setEventType(type.name());
        entity.setPayloadJson(Map.copyOf(enriched));
        entity.setCreatedAt(Instant.now());
        eventMapper.insert(entity);
        return new RunEvent(runId, sequence, type, Map.copyOf(enriched), entity.getCreatedAt());
    }

    private RunEvent toEvent(RunEventEntity entity) {
        RunEventType type;
        try {
            type = RunEventType.valueOf(entity.getEventType());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "run_event stored an unknown type: " + entity.getEventType(), ex);
        }
        return new RunEvent(
                entity.getRunId(),
                entity.getSequenceNo(),
                type,
                entity.getPayloadJson() == null ? Map.of() : entity.getPayloadJson(),
                entity.getCreatedAt());
    }

    private Map<String, Object> toJsonMap(RunCheckpoint checkpoint) {
        var map = new LinkedHashMap<String, Object>();
        map.put("runId", checkpoint.runId());
        map.put("checkpointType", checkpoint.checkpointType());
        map.put("schemaVersion", checkpoint.schemaVersion());
        map.put("lastCompletedRound", checkpoint.lastCompletedRound());
        map.put("lastPersistedEventSequence", checkpoint.lastPersistedEventSequence());
        map.put("createdAt", checkpoint.createdAt().toString());
        map.put("budgetSnapshot", Map.of(
                "modelCallsUsed", checkpoint.budgetSnapshot().modelCallsUsed(),
                "toolCallsUsed", checkpoint.budgetSnapshot().toolCallsUsed(),
                "tokensUsed", checkpoint.budgetSnapshot().tokensUsed(),
                "reactRoundsUsed", checkpoint.budgetSnapshot().reactRoundsUsed()));
        var artifacts = new java.util.ArrayList<Map<String, Object>>();
        for (var artifact : checkpoint.artifacts()) {
            var a = new LinkedHashMap<String, Object>();
            a.put("artifactId", artifact.artifactId());
            a.put("runId", artifact.runId());
            a.put("taskId", artifact.taskId());
            a.put("type", artifact.type().name());
            a.put("schemaVersion", artifact.schemaVersion());
            a.put("artifactVersion", artifact.artifactVersion());
            a.put("status", artifact.status().name());
            a.put("content", artifact.content());
            a.put("sourceRefs", List.copyOf(artifact.sourceRefs()));
            a.put("createdAt", artifact.createdAt().toString());
            artifacts.add(a);
        }
        map.put("artifacts", artifacts);
        return map;
    }

    private RunCheckpoint fromJsonMap(Map<String, Object> json) {
        var artifacts = new java.util.ArrayList<com.pulseink.agent.artifact.AgentArtifact>();
        for (Object item : castList(json.get("artifacts"))) {
            artifacts.add(toArtifact(castMap(item)));
        }
        var budget = castMap(json.get("budgetSnapshot"));
        return RunCheckpoint.of(
                castLong(json.get("runId")),
                String.valueOf(json.get("checkpointType")),
                List.copyOf(artifacts),
                new com.pulseink.agent.budget.BudgetSnapshot(
                        (int) castLong(budget.get("modelCallsUsed")),
                        (int) castLong(budget.get("toolCallsUsed")),
                        castLong(budget.get("tokensUsed")),
                        (int) castLong(budget.get("reactRoundsUsed"))),
                (int) castLong(json.get("lastCompletedRound")),
                castLong(json.get("lastPersistedEventSequence")),
                java.time.Instant.parse(String.valueOf(json.get("createdAt"))));
    }

    private static com.pulseink.agent.artifact.AgentArtifact toArtifact(Map<String, Object> json) {
        return com.pulseink.agent.artifact.AgentArtifact.restore(
                String.valueOf(json.get("artifactId")),
                castLong(json.get("runId")),
                String.valueOf(json.get("taskId")),
                com.pulseink.agent.artifact.ArtifactType.valueOf(
                        String.valueOf(json.get("type"))),
                String.valueOf(json.get("schemaVersion")),
                (int) castLong(json.get("artifactVersion")),
                com.pulseink.agent.artifact.ArtifactStatus.valueOf(
                        String.valueOf(json.get("status"))),
                castMap(json.get("content")),
                castList(json.get("sourceRefs")).stream()
                        .map(String::valueOf).toList(),
                java.time.Instant.parse(String.valueOf(json.get("createdAt"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    private static long castLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
