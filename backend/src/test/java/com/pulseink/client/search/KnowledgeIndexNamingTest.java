package com.pulseink.client.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.embedding.EmbeddingProfile;
import org.junit.jupiter.api.Test;

class KnowledgeIndexNamingTest {

    private final KnowledgeIndexNaming naming = new KnowledgeIndexNaming(
            "pulseink-knowledge-active");

    @Test
    void aliasIsFixedAndPhysicalIndexIsDeterministic() {
        assertThat(naming.alias()).isEqualTo("pulseink-knowledge-active");
        var profile = EmbeddingProfile.of("openai-compatible", "embed-3", 128);
        var first = naming.physicalIndex(profile);
        var second = naming.physicalIndex(EmbeddingProfile.of("openai-compatible", "embed-3", 128));
        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("pulseink-knowledge-v1-");
        assertThat(first).contains("embed-3");
        assertThat(first).hasSizeLessThan(255);
    }

    @Test
    void differentProfilesProduceDifferentIndices() {
        var a = naming.physicalIndex(EmbeddingProfile.of("fake", "m1", 64));
        var b = naming.physicalIndex(EmbeddingProfile.of("fake", "m1", 128));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void unsafeProfileCharactersAreSanitized() {
        var profile = EmbeddingProfile.of("openai-compatible", "my model/1", 64);
        var index = naming.physicalIndex(profile);
        assertThat(index).doesNotContain("/").doesNotContain(" ");
    }

    @Test
    void rejectsBlankAlias() {
        assertThatThrownBy(() -> new KnowledgeIndexNaming(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
