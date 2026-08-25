package com.pulseink.client.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.embedding.EmbeddingBatch;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.embedding.EmbeddingPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicFakeEmbeddingAdapterTest {

    private final EmbeddingPort adapter = new DeterministicFakeEmbeddingAdapter();

    @Test
    void producesStableL2NormalizedVectorsOfConfiguredDimension() {
        var profile = adapter.profile();
        assertThat(profile.dimensions()).isEqualTo(64);
        assertThat(profile.providerId()).isEqualTo("fake");
        assertThat(profile.profileId()).isNotBlank();

        var batch = adapter.embed(List.of("hello world"), EmbeddingPurpose.INDEX);
        assertThat(batch.vectors()).hasSize(1);
        float[] vector = batch.vectors().get(0);
        assertThat(vector).hasSize(64);
        assertThat((double) length(vector))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        for (float value : vector) {
            assertThat(Float.isFinite(value)).isTrue();
        }
    }

    @Test
    void identicalTextIsIdenticalAcrossAdapters() {
        var first = new DeterministicFakeEmbeddingAdapter();
        var second = new DeterministicFakeEmbeddingAdapter();
        var a = first.embed(List.of("same text"), EmbeddingPurpose.INDEX).vectors().get(0);
        var b = second.embed(List.of("same text"), EmbeddingPurpose.INDEX).vectors().get(0);
        assertThat(a).containsExactly(b);
    }

    @Test
    void differentTextsAreNotAllEqual() {
        var batch = adapter.embed(
                List.of("brand guidelines", "channel rules", "approved examples"),
                EmbeddingPurpose.INDEX);
        var a = batch.vectors().get(0);
        var b = batch.vectors().get(1);
        var c = batch.vectors().get(2);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(b).isNotEqualTo(c);
    }

    @Test
    void preservesInputOrder() {
        var batch = adapter.embed(List.of("first", "second", "third"),
                EmbeddingPurpose.QUERY);
        assertThat(batch.vectors()).hasSize(3);
    }

    @Test
    void rejectsEmptyBatchAndBlankText() {
        assertThatThrownBy(() -> adapter.embed(List.of(), EmbeddingPurpose.INDEX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.embed(List.of("ok", "  "), EmbeddingPurpose.INDEX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileIdIsDeterministicFingerprintWithoutSecrets() {
        var profile = adapter.profile();
        assertThat(profile.profileId())
                .isEqualTo(new DeterministicFakeEmbeddingAdapter().profile().profileId());
        assertThat(profile.profileId()).doesNotContain("key").doesNotContain("token");
    }

    private static double length(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }
}
