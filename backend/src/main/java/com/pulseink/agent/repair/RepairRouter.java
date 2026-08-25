package com.pulseink.agent.repair;

import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewIssueType;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Computes the smallest deterministic plan subgraph affected by a failed review. */
public final class RepairRouter {

    public RepairDecision route(ReviewAssessment assessment, PlanSpec plan,
                                int completedRepairRounds, int maxRepairRounds) {
        if (assessment == null || plan == null || assessment.passed()) {
            throw new IllegalArgumentException("only a failed review can be routed");
        }
        if (completedRepairRounds < 0) {
            throw new IllegalArgumentException("completedRepairRounds must not be negative");
        }
        if (maxRepairRounds < 0 || maxRepairRounds > 2) {
            throw new IllegalArgumentException("maxRepairRounds must be between 0 and 2");
        }
        if (completedRepairRounds >= maxRepairRounds) {
            return new RepairDecision(RepairPath.WAITING_HUMAN, Set.of(), Set.of(),
                    false, true, completedRepairRounds);
        }

        var tasks = index(plan);
        var roots = new TreeSet<String>();
        var selectedPath = RepairPath.CREATOR;
        for (var issue : assessment.issues()) {
            if (issue.type() == ReviewIssueType.PLAN_GAP
                    || issue.type() == ReviewIssueType.REPEATED_FAIL) {
                return replan(plan, completedRepairRounds);
            }
            switch (issue.type()) {
                case STYLE, FORMAT -> roots.addAll(issue.affectedTaskIds());
                case MISSING_EVIDENCE -> {
                    var ancestors = ancestorsWithRole(issue.affectedTaskIds(), tasks,
                            AgentRole.RESEARCHER);
                    if (ancestors.isEmpty()) {
                        return replan(plan, completedRepairRounds);
                    }
                    roots.addAll(ancestors);
                    selectedPath = RepairPath.RESEARCHER_TO_CREATOR;
                }
                case STRATEGY_MISMATCH -> {
                    var ancestors = ancestorsWithRole(issue.affectedTaskIds(), tasks,
                            AgentRole.STRATEGIST);
                    if (ancestors.isEmpty()) {
                        return replan(plan, completedRepairRounds);
                    }
                    roots.addAll(ancestors);
                    if (selectedPath != RepairPath.RESEARCHER_TO_CREATOR) {
                        selectedPath = RepairPath.STRATEGIST_TO_CREATOR;
                    }
                }
                case PLAN_GAP, REPEATED_FAIL -> throw new IllegalStateException("handled above");
            }
        }
        return new RepairDecision(selectedPath, roots, descendantsOf(roots, tasks),
                false, false, completedRepairRounds + 1);
    }

    private static RepairDecision replan(PlanSpec plan, int completedRounds) {
        var allTasks = new TreeSet<String>();
        for (var task : plan.tasks()) {
            allTasks.add(task.taskId());
        }
        return new RepairDecision(RepairPath.PLANNER_REPLAN, Set.of(), allTasks,
                true, false, completedRounds + 1);
    }

    private static Map<String, PlanTask> index(PlanSpec plan) {
        var result = new HashMap<String, PlanTask>();
        for (var task : plan.tasks()) {
            result.put(task.taskId(), task);
        }
        return result;
    }

    private static Set<String> ancestorsWithRole(Set<String> starts,
                                                  Map<String, PlanTask> tasks,
                                                  AgentRole role) {
        var result = new TreeSet<String>();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>(starts);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            var task = tasks.get(current);
            if (task == null) {
                continue;
            }
            if (task.role() == role) {
                result.add(current);
            }
            queue.addAll(task.dependsOn());
        }
        return result;
    }

    private static Set<String> descendantsOf(Set<String> roots,
                                              Map<String, PlanTask> tasks) {
        var result = new TreeSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var task : tasks.values()) {
                if (!result.contains(task.taskId())
                        && task.dependsOn().stream().anyMatch(result::contains)) {
                    changed |= result.add(task.taskId());
                }
            }
        } while (changed);
        return result;
    }
}
