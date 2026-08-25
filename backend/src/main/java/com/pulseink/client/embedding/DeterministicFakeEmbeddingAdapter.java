package com.pulseink.client.embedding;

import com.pulseink.service.embedding.EmbeddingBatch;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.embedding.EmbeddingPurpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic fake embedding. Uses stable feature hashing over code points plus L2
 * normalization; no randomness, network or wall clock. Identical text produces identical vectors
 * across JVM runs.
 */
public final class DeterministicFakeEmbeddingAdapter implements EmbeddingPort {

    private static final String PROVIDER_ID = "fake";
    private static final String MODEL_ID = "pulseink-fake-embed";

    private final int dimensions;
    private final EmbeddingProfile profile;

    public DeterministicFakeEmbeddingAdapter() {
        this(64);
    }

    public DeterministicFakeEmbeddingAdapter(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
        this.profile = EmbeddingProfile.of(PROVIDER_ID, MODEL_ID, dimensions);
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    @Override
    public EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("embedding batch must not be empty");
        }
        var vectors = new ArrayList<float[]>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("embedding text must not be blank");
            }
            vectors.add(vectorFor(text));
        }
        return new EmbeddingBatch(vectors);
    }

    private float[] vectorFor(String text) {
        float[] vector = new float[dimensions];
        text.codePoints().forEach(codePoint -> {
            byte[] digest = sha256(codePoint);
            long feature = hexLong(digest);
            int bucket = (int) ((feature & Long.MAX_VALUE) % dimensions);
            float value = ((feature >>> 63) == 0 ? 1f : -1f)
                    * ((feature >>> 32) & 0xFFFF) / 65536f;
            vector[bucket] += value;
        });
        // second feature pass over word starts for discrimination
        long wordAccumulator = 0;
        for (int codePoint : text.codePoints().toArray()) {
            wordAccumulator = wordAccumulator * 31 + codePoint;
            byte[] digest = sha256(wordAccumulator);
            int bucket = (int) ((hexLong(digest) & Long.MAX_VALUE) % dimensions);
            vector[bucket] += 0.5f;
        }
        normalize(vector);
        return vector;
    }

    private static long hexLong(byte[] digest) {
        String hex = HexFormat.of().formatHex(digest);
        return Long.parseLong(hex.substring(0, 15), 16);
    }

    private static byte[] sha256(long value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            vector[0] = 1f;
            return;
        }
        double length = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / length);
        }
    }
}
