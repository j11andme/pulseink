package com.pulseink.service.campaign;

import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.domain.campaign.CampaignRun;
import java.util.List;

public interface QueryRunUseCase {

    CampaignRun executionDecision(long runId);

    List<CampaignRun> history(long campaignId);

    RunTraceSnapshot trace(long runId);

    /**
     * Read-side snapshot assembled from the durable run row, the latest persisted checkpoint and
     * the ordered persisted event log. The frontend resumes SSE from {@code lastEventSequence}.
     */
    record RunTraceSnapshot(
            CampaignRun run,
            long lastEventSequence,
            RunCheckpoint checkpoint,
            List<RunEvent> events) {

        public RunTraceSnapshot {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(long runId) {
            super("run " + runId + " was not found");
        }
    }
}
