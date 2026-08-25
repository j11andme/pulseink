package com.pulseink.config.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class EmbeddingPropertiesTest {

    @Test
    void fakeDefaultsNeedNoSecrets() {
        var properties = new EmbeddingProperties(
                null, null, null, null, 0, null, 0, null);
        assertThat(properties.provider()).isEqualTo("fake");
        assertThat(properties.dimensions()).isEqualTo(64);
        assertThat(properties.dimensionField()).isEqualTo("dimensions");
        assertThat(properties.batchSize()).isEqualTo(16);
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void explicitValuesAreBound() {
        var environment = new MockEnvironment()
                .withProperty("pulseink.embedding.provider", "openai-compatible")
                .withProperty("pulseink.embedding.base-url", "https://example.com/v1")
                .withProperty("pulseink.embedding.model", "embed-3")
                .withProperty("pulseink.embedding.dimensions", "128")
                .withProperty("pulseink.embedding.dimension-field", "dimension");
        var properties = bind(environment);
        assertThat(properties.provider()).isEqualTo("openai-compatible");
        assertThat(properties.baseUrl()).isEqualTo("https://example.com/v1");
        assertThat(properties.model()).isEqualTo("embed-3");
        assertThat(properties.dimensions()).isEqualTo(128);
        assertThat(properties.dimensionField()).isEqualTo("dimension");
    }

    @Test
    void rejectsInvalidDimensionField() {
        var environment = new MockEnvironment()
                .withProperty("pulseink.embedding.dimension-field", "size");
        assertThatThrownBy(() -> bind(environment))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidDimensions() {
        var environment = new MockEnvironment()
                .withProperty("pulseink.embedding.dimensions", "-5");
        assertThatThrownBy(() -> bind(environment))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private static EmbeddingProperties bind(MockEnvironment environment) {
        return Binder.get(environment)
                .bind("pulseink.embedding", Bindable.of(EmbeddingProperties.class))
                .orElseThrow(() -> new IllegalStateException("embedding properties not bound"));
    }
}
