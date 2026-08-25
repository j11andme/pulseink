package com.pulseink.domain.campaign;

import com.pulseink.domain.execution.ExecutionDecision;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CampaignRun {

    private final long id;
    private final long campaignId;
    private final ExecutionPolicy requestedPolicy;
    private RunState state;
    private ExecutionMode selectedMode;
    private String selectorPolicyVersion;
    private List<String> selectionReasonCodes;
    private Map<String, Object> selectionFeatureSnapshot;
    private long estimatedTokenBudget;
    private String failureReason;
    private long version;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;

    private CampaignRun(long id, long campaignId, ExecutionPolicy requestedPolicy) {
        if (campaignId <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        this.id = id;
        this.campaignId = campaignId;
        this.requestedPolicy =
                Objects.requireNonNull(requestedPolicy, "requested policy must not be null");
        this.state = RunState.CREATED;
        this.selectionReasonCodes = List.of();
        this.selectionFeatureSnapshot = Map.of();
    }

    public static CampaignRun create(long campaignId, ExecutionPolicy requestedPolicy) {
        return new CampaignRun(0L, campaignId, requestedPolicy);
    }

    public static CampaignRun materialize(
            long id,
            long campaignId,
            ExecutionPolicy requestedPolicy,
            RunState state,
            ExecutionMode selectedMode,
            String selectorPolicyVersion,
            List<String> selectionReasonCodes,
            Map<String, Object> selectionFeatureSnapshot,
            long estimatedTokenBudget,
            String failureReason,
            long version,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("run id must be positive");
        }
        var run = new CampaignRun(id, campaignId, requestedPolicy);
        run.state = Objects.requireNonNull(state, "run state must not be null");
        run.selectedMode = selectedMode;
        run.selectorPolicyVersion = selectorPolicyVersion;
        run.selectionReasonCodes = selectionReasonCodes == null
                ? List.of()
                : List.copyOf(selectionReasonCodes);
        run.selectionFeatureSnapshot = selectionFeatureSnapshot == null
                ? Map.of()
                : Map.copyOf(selectionFeatureSnapshot);
        run.estimatedTokenBudget = estimatedTokenBudget;
        run.failureReason = failureReason;
        run.version = version;
        run.startedAt = startedAt;
        run.completedAt = completedAt;
        run.createdAt = Objects.requireNonNull(createdAt, "run createdAt must not be null");
        run.updatedAt = Objects.requireNonNull(updatedAt, "run updatedAt must not be null");
        return run;
    }

    public void select(ExecutionDecision decision) {
        requireState(RunState.CREATED, "cannot select while run is ");
        var selection = Objects.requireNonNull(decision, "execution decision must not be null");
        if (selectedMode != null) {
            throw new IllegalStateException("execution decision has already been recorded");
        }

        selectedMode = selection.selectedMode();
        selectorPolicyVersion = selection.selectorPolicyVersion();
        selectionReasonCodes = selection.reasonCodes();
        selectionFeatureSnapshot = selection.featureSnapshot();
        estimatedTokenBudget = selection.estimatedTokenBudget();
    }

    public void start(ExecutionDecision decision) {
        requireState(RunState.CREATED, "cannot start while run is ");
        var selection = Objects.requireNonNull(decision, "execution decision must not be null");

        selectedMode = selection.selectedMode();
        selectorPolicyVersion = selection.selectorPolicyVersion();
        selectionReasonCodes = selection.reasonCodes();
        selectionFeatureSnapshot = selection.featureSnapshot();
        estimatedTokenBudget = selection.estimatedTokenBudget();
        state = RunState.RUNNING;
    }

    /**
     * Enters RUNNING only from a CREATED run that already has a selected mode.
     */
    public void beginExecution(Instant startedAt) {
        requireState(RunState.CREATED, "cannot begin execution while run is ");
        if (selectedMode == null) {
            throw new IllegalStateException("run has no selected execution mode");
        }
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        state = RunState.RUNNING;
    }

    public void waitForHuman() {
        requireState(RunState.RUNNING, "cannot wait for human while run is ");
        state = RunState.WAITING_HUMAN;
    }

    public void requestApproval() {
        requireState(RunState.RUNNING, "cannot request approval while run is ");
        state = RunState.WAITING_APPROVAL;
    }

    /** Resumes a human-repaired result for explicit approval without rerunning agents. */
    public void resumeForApproval() {
        requireState(RunState.WAITING_HUMAN, "cannot resume approval while run is ");
        state = RunState.WAITING_APPROVAL;
    }

    public void beginPublishing() {
        if (state != RunState.WAITING_APPROVAL) {
            throw new IllegalStateException("run must be approved before publishing");
        }
        state = RunState.PUBLISHING;
    }

    /** Returns a permanently rejected publication to the human correction workflow. */
    public void waitForPublicationCorrection() {
        requireState(RunState.PUBLISHING,
                "cannot wait for publication correction while run is ");
        state = RunState.WAITING_HUMAN;
    }

    public void complete(Instant completedAt) {
        requireState(RunState.PUBLISHING, "cannot complete while run is ");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        state = RunState.COMPLETED;
    }

    public void fail(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failure reason must not be blank");
        }
        if (state.isTerminal()) {
            throw new IllegalStateException("cannot fail while run is " + state);
        }
        failureReason = reason.strip();
        state = RunState.FAILED;
    }

    private void requireState(RunState expected, String messagePrefix) {
        if (state != expected) {
            throw new IllegalStateException(messagePrefix + state);
        }
    }

    public long id() {
        return id;
    }

    public long campaignId() {
        return campaignId;
    }

    public ExecutionPolicy requestedPolicy() {
        return requestedPolicy;
    }

    public RunState state() {
        return state;
    }

    public ExecutionMode selectedMode() {
        return selectedMode;
    }

    public String selectorPolicyVersion() {
        return selectorPolicyVersion;
    }

    public List<String> selectionReasonCodes() {
        return selectionReasonCodes;
    }

    public Map<String, Object> selectionFeatureSnapshot() {
        return selectionFeatureSnapshot;
    }

    public long estimatedTokenBudget() {
        return estimatedTokenBudget;
    }

    public String failureReason() {
        return failureReason;
    }

    public long version() {
        return version;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
