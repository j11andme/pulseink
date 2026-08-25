package com.pulseink.agent.api;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.repair.RepairPath;
import com.pulseink.domain.content.ReviewIssueType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sealed hierarchy of runtime events emitted during agent execution. No event carries prompts,
 * hidden chain-of-thought, credentials or provider stack traces.
 */
public sealed interface AgentRuntimeEvent
        permits AgentRuntimeEvent.DecisionRecorded,
                AgentRuntimeEvent.ToolCallStarted,
                AgentRuntimeEvent.ToolCallCompleted,
                AgentRuntimeEvent.ArtifactCompleted,
                AgentRuntimeEvent.RuntimeFailed,
                AgentRuntimeEvent.TaskStarted,
                AgentRuntimeEvent.TaskCompleted,
                AgentRuntimeEvent.ReviewIssueCreated,
                AgentRuntimeEvent.RepairRoundStarted,
                AgentRuntimeEvent.ArtifactInvalidated,
                AgentRuntimeEvent.RepairExhausted {

    long runId();
    Instant timestamp();

    record DecisionRecorded(
            long runId,
            Instant timestamp,
            String decisionType,
            String decisionSummary) implements AgentRuntimeEvent {
        public DecisionRecorded {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(decisionType, "decisionType must not be null");
            Objects.requireNonNull(decisionSummary, "decisionSummary must not be null");
        }
    }

    record ToolCallStarted(
            long runId,
            Instant timestamp,
            String qualifiedName,
            Map<String, Object> arguments) implements AgentRuntimeEvent {
        public ToolCallStarted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(qualifiedName, "qualifiedName must not be null");
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record ToolCallCompleted(
            long runId,
            Instant timestamp,
            String qualifiedName,
            String observationSummary,
            Map<String, String> metadata) implements AgentRuntimeEvent {
        public ToolCallCompleted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(qualifiedName, "qualifiedName must not be null");
            observationSummary = observationSummary == null ? "" : observationSummary;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record ArtifactCompleted(
            long runId,
            Instant timestamp,
            AgentArtifact artifact,
            BudgetSnapshot budgetSnapshot,
            int completedRound) implements AgentRuntimeEvent {
        public ArtifactCompleted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(artifact, "artifact must not be null");
            Objects.requireNonNull(budgetSnapshot, "budgetSnapshot must not be null");
            if (completedRound < 0) {
                throw new IllegalArgumentException("completedRound must not be negative");
            }
        }
    }

    record RuntimeFailed(
            long runId,
            Instant timestamp,
            AgentTerminalReason reason,
            String message) implements AgentRuntimeEvent {
        public RuntimeFailed {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            message = message == null ? "" : message;
        }
    }

    record TaskStarted(
            long runId,
            Instant timestamp,
            String taskId,
            AgentRole role) implements AgentRuntimeEvent {
        public TaskStarted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            role = Objects.requireNonNull(role, "role must not be null");
        }
    }

    record TaskCompleted(
            long runId,
            Instant timestamp,
            String taskId,
            AgentRole role) implements AgentRuntimeEvent {
        public TaskCompleted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            role = Objects.requireNonNull(role, "role must not be null");
        }
    }

    record ReviewIssueCreated(
            long runId,
            Instant timestamp,
            ReviewIssueType issueType,
            Set<String> affectedTaskIds,
            int repairRound) implements AgentRuntimeEvent {
        public ReviewIssueCreated {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            issueType = Objects.requireNonNull(issueType, "issueType must not be null");
            affectedTaskIds = affectedTaskIds == null
                    ? Set.of() : Set.copyOf(affectedTaskIds);
            if (repairRound < 0) {
                throw new IllegalArgumentException("repairRound must not be negative");
            }
        }
    }

    record RepairRoundStarted(
            long runId,
            Instant timestamp,
            int repairRound,
            RepairPath path,
            Set<String> rootTaskIds) implements AgentRuntimeEvent {
        public RepairRoundStarted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            path = Objects.requireNonNull(path, "path must not be null");
            rootTaskIds = rootTaskIds == null ? Set.of() : Set.copyOf(rootTaskIds);
            if (repairRound <= 0) {
                throw new IllegalArgumentException("repairRound must be positive");
            }
        }
    }

    record ArtifactInvalidated(
            long runId,
            Instant timestamp,
            AgentArtifact artifact,
            BudgetSnapshot budgetSnapshot,
            int repairRound) implements AgentRuntimeEvent {
        public ArtifactInvalidated {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(artifact, "artifact must not be null");
            Objects.requireNonNull(budgetSnapshot, "budgetSnapshot must not be null");
            if (artifact.status() != com.pulseink.agent.artifact.ArtifactStatus.INVALIDATED) {
                throw new IllegalArgumentException("artifact snapshot must be INVALIDATED");
            }
            if (repairRound <= 0) {
                throw new IllegalArgumentException("repairRound must be positive");
            }
        }
    }

    record RepairExhausted(
            long runId,
            Instant timestamp,
            int completedRepairRounds) implements AgentRuntimeEvent {
        public RepairExhausted {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            if (completedRepairRounds < 0) {
                throw new IllegalArgumentException(
                        "completedRepairRounds must not be negative");
            }
        }
    }
}
