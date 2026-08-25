package com.pulseink.agent.repair;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.domain.content.ReviewAssessment;

public interface ReviewArtifactInterpreter {

    ReviewAssessment interpret(AgentArtifact reviewArtifact, PlanSpec plan);
}
