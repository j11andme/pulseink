package com.pulseink.agent.plan;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable topological plan validation. Rejects structural, access, role/output and reachability
 * violations; returns deterministic stages (stage-internal order is taskId ASC, stages follow
 * topological order). Cycle detection uses Kahn's algorithm, never the input order.
 */
public final class PlanValidator {

    private static final Map<AgentRole, ArtifactType> ROLE_OUTPUTS = Map.of(
            AgentRole.RESEARCHER, ArtifactType.EVIDENCE_PACK,
            AgentRole.STRATEGIST, ArtifactType.CONTENT_STRATEGY,
            AgentRole.CREATOR, ArtifactType.CONTENT_DRAFT,
            AgentRole.REVIEWER, ArtifactType.REVIEW_REPORT);

    private final int maxTasks;

    public PlanValidator(int maxTasks) {
        if (maxTasks <= 0) {
            throw new IllegalArgumentException("maxTasks must be positive");
        }
        this.maxTasks = maxTasks;
    }

    public List<List<PlanTask>> validate(PlanSpec plan, Set<ArtifactType> initialArtifactTypes) {
        Objects.requireNonNull(plan, "plan must not be null");
        if (plan.schemaVersion() != PlanSpec.SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported plan schemaVersion: " + plan.schemaVersion());
        }
        var tasks = plan.tasks();
        if (tasks.isEmpty() || tasks.size() > maxTasks) {
            throw new IllegalArgumentException(
                    "plan must contain between 1 and " + maxTasks + " tasks");
        }

        var byId = new LinkedHashMap<String, PlanTask>();
        for (var task : tasks) {
            if (byId.put(task.taskId(), task) != null) {
                throw new IllegalArgumentException("duplicate taskId: " + task.taskId());
            }
        }

        for (var task : tasks) {
            if (task.role() == AgentRole.PLANNER) {
                throw new IllegalArgumentException(
                        "task " + task.taskId() + " must not use PLANNER role");
            }
            if (task.access() != PlanTaskAccess.READ_ONLY) {
                throw new IllegalArgumentException(
                        "task " + task.taskId() + " has SIDE_EFFECT access; only READ_ONLY allowed");
            }
            ArtifactType expected = ROLE_OUTPUTS.get(task.role());
            if (expected == null || task.outputArtifactType() != expected) {
                throw new IllegalArgumentException(
                        "task " + task.taskId() + " role " + task.role()
                                + " must output " + expected);
            }
            var seenDeps = new HashSet<String>();
            for (String dependency : task.dependsOn()) {
                if (dependency.equals(task.taskId())) {
                    throw new IllegalArgumentException(
                            "cycle: task " + task.taskId() + " depends on itself");
                }
                if (!seenDeps.add(dependency)) {
                    throw new IllegalArgumentException(
                            "task " + task.taskId() + " has duplicate dependency " + dependency);
                }
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "task " + task.taskId() + " has unknown dependency " + dependency);
                }
            }
        }

        if (!byId.values().stream().anyMatch(t -> t.role() == AgentRole.CREATOR)) {
            throw new IllegalArgumentException("plan must contain a CREATOR task");
        }

        var reachableTypes = reachableTypes(byId, initialArtifactTypes);
        for (var task : tasks) {
            var provided = reachableTypes.get(task.taskId());
            for (var required : task.requiredArtifactTypes()) {
                if (!provided.contains(required)) {
                    throw new IllegalArgumentException(
                            "task " + task.taskId() + " requires unavailable artifact "
                                    + required + " (required artifact unreachable)");
                }
            }
            if (task.role() == AgentRole.CREATOR
                    && !provided.contains(ArtifactType.CONTENT_STRATEGY)) {
                throw new IllegalArgumentException(
                        "task " + task.taskId() + " (CREATOR) requires CONTENT_STRATEGY");
            }
            if (task.role() == AgentRole.REVIEWER
                    && !provided.contains(ArtifactType.CONTENT_DRAFT)) {
                throw new IllegalArgumentException(
                        "task " + task.taskId() + " (REVIEWER) requires CONTENT_DRAFT");
            }
        }

        return topoStages(byId);
    }

    private Map<String, Set<ArtifactType>> reachableTypes(
            Map<String, PlanTask> byId, Set<ArtifactType> initial) {
        var initialSet = Set.copyOf(initial == null ? Set.<ArtifactType>of() : initial);
        var result = new LinkedHashMap<String, Set<ArtifactType>>();
        for (var taskId : byId.keySet()) {
            result.put(taskId, reachable(taskId, byId, initialSet, new HashSet<>()));
        }
        return result;
    }

    private Set<ArtifactType> reachable(String taskId, Map<String, PlanTask> byId,
                                        Set<ArtifactType> initial, Set<String> visiting) {
        if (visiting.contains(taskId)) {
            throw new IllegalArgumentException("plan contains a dependency cycle");
        }
        visiting.add(taskId);
        var task = byId.get(taskId);
        var types = new HashSet<>(initial);
        for (var dependency : task.dependsOn()) {
            types.add(byId.get(dependency).outputArtifactType());
            types.addAll(reachable(dependency, byId, initial, visiting));
        }
        visiting.remove(taskId);
        return types;
    }

    private List<List<PlanTask>> topoStages(Map<String, PlanTask> byId) {
        var completed = new HashSet<String>();
        var remaining = new HashSet<>(byId.keySet());
        var stages = new ArrayList<List<PlanTask>>();
        while (!remaining.isEmpty()) {
            var stage = new TreeSet<String>();
            for (var taskId : remaining) {
                if (completed.containsAll(byId.get(taskId).dependsOn())) {
                    stage.add(taskId);
                }
            }
            if (stage.isEmpty()) {
                throw new IllegalArgumentException("plan contains a dependency cycle");
            }
            var stageTasks = new ArrayList<PlanTask>();
            for (var taskId : stage) {
                stageTasks.add(byId.get(taskId));
                completed.add(taskId);
                remaining.remove(taskId);
            }
            stages.add(List.copyOf(stageTasks));
        }
        return List.copyOf(stages);
    }
}
