package com.pulseink.config.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class KnowledgePropertiesTest {

    @Test
    void defaultsAreSaneAndValid() {
        var properties = new KnowledgeProperties(
                null, 0, 0, 0, 120, 0, null, 0, 0, 0, null);
        assertThat(properties.storageRoot())
                .isEqualTo(Path.of("./data/knowledge").toAbsolutePath().normalize());
        assertThat(properties.maxFileBytes()).isEqualTo(10 * 1024 * 1024L);
        assertThat(properties.maxExtractedCharacters()).isEqualTo(2_000_000L);
        assertThat(properties.maxChunkCodePoints()).isEqualTo(1000);
        assertThat(properties.chunkOverlap()).isEqualTo(120);
        assertThat(properties.maxChunks()).isEqualTo(2000);
        assertThat(properties.staleJobTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void explicitValuesAreBound() {
        var environment = new MockEnvironment()
                .withProperty("pulseink.knowledge.storage-root", "C:/tmp/knowledge")
                .withProperty("pulseink.knowledge.max-file-bytes", "1048576")
                .withProperty("pulseink.knowledge.max-chunk-code-points", "500")
                .withProperty("pulseink.knowledge.chunk-overlap", "50");
        var properties = bind(environment);
        assertThat(properties.storageRoot())
                .isEqualTo(Path.of("C:/tmp/knowledge").toAbsolutePath().normalize());
        assertThat(properties.maxFileBytes()).isEqualTo(1_048_576L);
        assertThat(properties.maxChunkCodePoints()).isEqualTo(500);
        assertThat(properties.chunkOverlap()).isEqualTo(50);
    }

    @Test
    void invalidOverlapIsRejected() {
        var environment = new MockEnvironment()
                .withProperty("pulseink.knowledge.chunk-overlap", "1001");
        assertThatThrownBy(() -> bind(environment))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitZeroOverlapIsPreserved() {
        var environment = new MockEnvironment()
                .withProperty("pulseink.knowledge.chunk-overlap", "0");

        assertThat(bind(environment).chunkOverlap()).isZero();
    }

    private static KnowledgeProperties bind(MockEnvironment environment) {
        return Binder.get(environment)
                .bind("pulseink.knowledge", Bindable.of(KnowledgeProperties.class))
                .orElseThrow(() -> new IllegalStateException("knowledge properties not bound"));
    }
}
