package com.pulseink.agent.memory;

import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import java.util.Set;

/**
 * Deterministic minimal-context policy per logical role. The section set is fixed per role;
 * nothing outside the policy can enter the rendered context.
 */
public enum RoleContextPolicy {

    PLANNER(Set.of(ContextSection.BRIEF, ContextSection.CURRENT_OBJECTIVE,
            ContextSection.WORKING_MEMORY, ContextSection.APPROVED_INSIGHTS,
            ContextSection.SOURCE_LABELS), 3),
    RESEARCHER(Set.of(ContextSection.CURRENT_OBJECTIVE, ContextSection.DEPENDENCY_ARTIFACTS,
            ContextSection.SOURCE_LABELS), 0),
    STRATEGIST(Set.of(ContextSection.BRIEF, ContextSection.CURRENT_OBJECTIVE,
            ContextSection.DEPENDENCY_ARTIFACTS, ContextSection.EPISODIC_SUMMARY,
            ContextSection.APPROVED_INSIGHTS, ContextSection.SOURCE_LABELS), 3),
    CREATOR(Set.of(ContextSection.BRIEF, ContextSection.CURRENT_OBJECTIVE,
            ContextSection.DEPENDENCY_ARTIFACTS, ContextSection.APPROVED_INSIGHTS,
            ContextSection.SOURCE_LABELS), 3),
    REVIEWER(Set.of(ContextSection.CURRENT_OBJECTIVE, ContextSection.DEPENDENCY_ARTIFACTS,
            ContextSection.SOURCE_LABELS), 0),
    UNIFIED(Set.of(ContextSection.BRIEF, ContextSection.CURRENT_OBJECTIVE,
            ContextSection.WORKING_MEMORY, ContextSection.DEPENDENCY_ARTIFACTS,
            ContextSection.EPISODIC_SUMMARY, ContextSection.APPROVED_INSIGHTS,
            ContextSection.SOURCE_LABELS), 3);

    private final Set<ContextSection> sections;
    private final int maxApprovedInsights;

    RoleContextPolicy(Set<ContextSection> sections, int maxApprovedInsights) {
        this.sections = Set.copyOf(sections);
        this.maxApprovedInsights = maxApprovedInsights;
    }

    public boolean includes(ContextSection section) {
        return sections.contains(section);
    }

    public int maxApprovedInsights() {
        return maxApprovedInsights;
    }

    public static RoleContextPolicy forProfile(AgentProfile profile) {
        if (profile.roles().size() == 1) {
            AgentRole role = profile.role();
            return switch (role) {
                case PLANNER -> PLANNER;
                case RESEARCHER -> RESEARCHER;
                case STRATEGIST -> STRATEGIST;
                case CREATOR -> CREATOR;
                case REVIEWER -> REVIEWER;
            };
        }
        return UNIFIED;
    }
}
