package com.pulseink.agent.orchestration;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic renderer of the role task context: campaign brief plus VALID dependency
 * artifacts ordered by taskId then artifactVersion, truncated to a code-point budget. Never
 * renders prompts, tokens, keys, storage keys, vectors or hidden reasoning.
 */
public final class ArtifactContextRenderer {

    private final int maxContextCodePoints;

    public ArtifactContextRenderer(int maxContextCodePoints) {
        if (maxContextCodePoints <= 0) {
            throw new IllegalArgumentException("maxContextCodePoints must be positive");
        }
        this.maxContextCodePoints = maxContextCodePoints;
    }

    public String render(String campaignContext, List<AgentArtifact> artifacts) {
        Objects.requireNonNull(campaignContext, "campaignContext must not be null");
        return truncate("Brief: " + campaignContext + "\n" + renderArtifacts(artifacts),
                maxContextCodePoints);
    }

    /**
     * Deterministic rendering of the VALID dependency artifacts only, ordered by taskId then
     * artifactVersion and truncated to the code point budget. Used by the ContextAssembler for
     * its DEPENDENCY_ARTIFACTS section; INVALIDATED artifacts never appear.
     */
    public String renderArtifacts(List<AgentArtifact> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts must not be null");
        var valid = new ArrayList<AgentArtifact>();
        for (var artifact : artifacts) {
            if (artifact.status() == ArtifactStatus.VALID) {
                valid.add(artifact);
            }
        }
        valid.sort(Comparator
                .comparing(AgentArtifact::taskId)
                .thenComparingInt(AgentArtifact::artifactVersion));

        var builder = new StringBuilder();
        for (var artifact : valid) {
            builder.append("Artifact taskId=").append(artifact.taskId())
                    .append(" type=").append(artifact.type().name())
                    .append(" schemaVersion=").append(artifact.schemaVersion())
                    .append(" version=").append(artifact.artifactVersion())
                    .append(" content=").append(artifact.content())
                    .append(" sourceRefs=").append(artifact.sourceRefs())
                    .append('\n');
            if (builder.codePointCount(0, builder.length()) >= maxContextCodePoints) {
                break;
            }
        }
        return truncate(builder.toString(), maxContextCodePoints);
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        if (maxCodePoints <= 3) {
            return ".".repeat(maxCodePoints);
        }
        int end = text.offsetByCodePoints(0, Math.max(0, maxCodePoints - 3));
        return text.substring(0, end) + "...";
    }
}
