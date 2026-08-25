package com.pulseink.service.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable batch of float vectors with validated count, dimension and finiteness.
 */
public record EmbeddingBatch(List<float[]> vectors) {

    public EmbeddingBatch {
        vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors must not be null"));
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("embedding batch must not be empty");
        }
        int dimension = vectors.get(0).length;
        if (dimension <= 0) {
            throw new IllegalArgumentException("embedding dimension must be positive");
        }
        for (float[] vector : vectors) {
            if (vector.length != dimension) {
                throw new IllegalArgumentException("embedding dimension drift in batch");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("embedding contains non-finite value");
                }
            }
        }
    }

    public List<float[]> vectorsCopy() {
        var copy = new ArrayList<float[]>(vectors.size());
        for (float[] vector : vectors) {
            copy.add(vector.clone());
        }
        return List.copyOf(copy);
    }
}
