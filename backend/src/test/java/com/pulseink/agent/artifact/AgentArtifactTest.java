package com.pulseink.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentArtifactTest {

    @Test
    void artifactFieldsAreAccessible() {
        var now = Instant.now();
        var artifact = AgentArtifact.create(
                "art-1", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("title", "Hello"), List.of("ref-1"), now);

        assertThat(artifact.artifactId()).isEqualTo("art-1");
        assertThat(artifact.runId()).isEqualTo(1L);
        assertThat(artifact.taskId()).isEqualTo("unified");
        assertThat(artifact.type()).isEqualTo(ArtifactType.CONTENT_DRAFT);
        assertThat(artifact.schemaVersion()).isEqualTo("artifact-v1");
        assertThat(artifact.artifactVersion()).isEqualTo(1);
        assertThat(artifact.status()).isEqualTo(ArtifactStatus.VALID);
        assertThat(artifact.content()).containsEntry("title", "Hello");
        assertThat(artifact.sourceRefs()).containsExactly("ref-1");
        assertThat(artifact.createdAt()).isEqualTo(now);
    }

    @Test
    void artifactDefensivelyCopiesMutableInputs() {
        var content = new HashMap<String, Object>();
        content.put("nested", new ArrayList<>(List.of("a")));
        var refs = new ArrayList<>(List.of("r1"));

        var artifact = AgentArtifact.create(
                "art-1", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, content, refs, Instant.now());

        content.put("extra", "x");
        @SuppressWarnings("unchecked")
        List<Object> nestedView = (List<Object>) content.get("nested");
        nestedView.add("b");
        refs.add("r2");

        assertThat(artifact.content()).doesNotContainKey("extra");
        @SuppressWarnings("unchecked")
        List<Object> nestedCopy = (List<Object>) artifact.content().get("nested");
        assertThat(nestedCopy).containsExactly("a");
        assertThat(artifact.sourceRefs()).containsExactly("r1");
    }

    @Test
    void artifactContentAndSourceRefsAreImmutable() {
        var artifact = AgentArtifact.create(
                "art-1", 1L, "unified", ArtifactType.EVIDENCE_PACK,
                1, Map.of("k", "v"), List.of("r"), Instant.now());

        assertThatThrownBy(() -> artifact.content().put("z", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> artifact.sourceRefs().add("z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deepImmutableNestedContent() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("list", new ArrayList<>(List.of(1, 2)));
        var artifact = AgentArtifact.create(
                "art-1", 1L, "unified", ArtifactType.CONTENT_STRATEGY,
                1, nested, List.of(), Instant.now());

        @SuppressWarnings("unchecked")
        List<Object> listView = (List<Object>) artifact.content().get("list");
        assertThatThrownBy(() -> listView.add(3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankArtifactId() {
        assertThatThrownBy(() -> AgentArtifact.create(
                "", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentArtifact.create(
                null, 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveRunIdAndVersion() {
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 0L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", -1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                0, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                -1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankTaskId() {
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of(), List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, null, List.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullMandatoryFields() {
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "unified", null,
                1, Map.of("k", "v"), List.of(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgentArtifact.create(
                "a", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void invalidationProducesNewStatus() {
        var artifact = AgentArtifact.create(
                "a", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of(), Instant.now());
        var invalidated = artifact.withStatus(ArtifactStatus.INVALIDATED);
        assertThat(artifact.status()).isEqualTo(ArtifactStatus.VALID);
        assertThat(invalidated.status()).isEqualTo(ArtifactStatus.INVALIDATED);
    }

    @Test
    void artifactTypeHasExpectedValues() {
        assertThat(java.util.EnumSet.allOf(ArtifactType.class)).containsExactlyInAnyOrder(
                ArtifactType.PLAN,
                ArtifactType.EVIDENCE_PACK,
                ArtifactType.CONTENT_STRATEGY,
                ArtifactType.CONTENT_DRAFT,
                ArtifactType.REVIEW_REPORT);
    }

    @Test
    void artifactStatusHasExpectedValues() {
        assertThat(java.util.EnumSet.allOf(ArtifactStatus.class)).containsExactlyInAnyOrder(
                ArtifactStatus.VALID,
                ArtifactStatus.INVALIDATED);
    }
}
