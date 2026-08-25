package com.pulseink.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactContextRendererTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private final ArtifactContextRenderer renderer = new ArtifactContextRenderer(12000);

    private AgentArtifact artifact(String id, String taskId, ArtifactType type, int version,
                                   ArtifactStatus status) {
        return AgentArtifact.restore(
                id, 1L, taskId, type, "artifact-v1", version, status,
                Map.of("title", "Content-" + taskId + "-" + version),
                List.of("ref-" + taskId), NOW);
    }

    @Test
    void rendersOnlyValidArtifactsWithFixedFields() {
        var valid = artifact("a1", "research-a", ArtifactType.EVIDENCE_PACK, 1,
                ArtifactStatus.VALID);
        var invalidated = artifact("a2", "research-b", ArtifactType.EVIDENCE_PACK, 1,
                ArtifactStatus.INVALIDATED);

        String rendered = renderer.render("brief", List.of(valid, invalidated));

        assertThat(rendered).contains("research-a")
                .contains("EVIDENCE_PACK")
                .contains("artifact-v1")
                .contains("ref-research-a")
                .doesNotContain("research-b")
                .doesNotContain("prompt")
                .doesNotContain("token")
                .doesNotContain("storageKey")
                .doesNotContain("vector");
    }

    @Test
    void sortsDeterministicallyByTaskIdThenVersion() {
        var later = artifact("a1", "task-z", ArtifactType.CONTENT_DRAFT, 1,
                ArtifactStatus.VALID);
        var earlier = artifact("a2", "task-a", ArtifactType.CONTENT_DRAFT, 2,
                ArtifactStatus.VALID);

        String rendered = renderer.render("brief", List.of(later, earlier));

        assertThat(rendered.indexOf("task-a")).isLessThan(rendered.indexOf("task-z"));
    }

    @Test
    void preservesSourceRefs() {
        var artifact = AgentArtifact.restore(
                "a1", 1L, "task-c", ArtifactType.CONTENT_DRAFT, "artifact-v1", 1,
                ArtifactStatus.VALID, Map.of("body", "x"), List.of("ref-1", "ref-2"), NOW);
        String rendered = renderer.render("brief", List.of(artifact));
        assertThat(rendered).contains("ref-1").contains("ref-2");
    }

    @Test
    void truncatesAtMaxCodePoints() {
        var renderer = new ArtifactContextRenderer(100);
        var artifact = AgentArtifact.restore(
                "a1", 1L, "task-big", ArtifactType.CONTENT_DRAFT, "artifact-v1", 1,
                ArtifactStatus.VALID, Map.of("body", "x".repeat(500)), List.of("r1"), NOW);
        String rendered = renderer.render("brief", List.of(artifact));
        assertThat(rendered.codePointCount(0, rendered.length()))
                .isLessThanOrEqualTo(100);
    }

    @Test
    void emptyArtifactsRenderBriefOnly() {
        String rendered = renderer.render("brief", List.of());
        assertThat(rendered).contains("brief");
    }

    @Test
    void rejectsInvalidMaxContext() {
        assertThatThrownBy(() -> new ArtifactContextRenderer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
