package com.pulseink.service.campaign;

import com.pulseink.agent.checkpoint.RunCheckpoint;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only event/checkpoint port. Sequence generation locks the owning campaign_run row in
 * the same transaction before computing the next sequence; checkpoints and their ARTIFACT_CREATED
 * event are written atomically.
 */
public interface RunJournal {

    RunEvent appendEvent(long runId, RunEventType type, Map<String, Object> payload);

    RunEvent saveCheckpointAndAppendEvent(
            RunCheckpoint checkpoint,
            RunEventType type,
            Map<String, Object> payload);

    Optional<RunCheckpoint> latestCheckpoint(long runId);

    List<RunEvent> findEventsAfter(long runId, long lastSequence);
}
