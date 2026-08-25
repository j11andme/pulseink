package com.pulseink.client.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.service.embedding.EmbeddingBatch;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.embedding.EmbeddingPurpose;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * OpenAI-compatible embedding adapter. POSTs {@code {model, input, <dimension field>}} to
 * {@code {baseUrl}/embeddings}, restores order from {@code data[].index}, validates counts,
 * indices, finiteness and dimensions, and maps HTTP/timeout/malformed failures to sanitized
 * domain exceptions.
 */
public final class OpenAiCompatibleEmbeddingAdapter implements EmbeddingPort {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final String dimensionField;
    private final int batchSize;
    private final EmbeddingProfile profile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingAdapter(String baseUrl, String apiKey, String model,
                                            int dimensions, String dimensionField,
                                            int batchSize, Duration timeout) {
        this.baseUrl = requireText(baseUrl, "baseUrl");
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
        if (!Set.of("dimension", "dimensions", "none").contains(dimensionField)) {
            throw new IllegalArgumentException("unsupported dimension field: " + dimensionField);
        }
        this.dimensionField = dimensionField;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.profile = EmbeddingProfile.of("openai-compatible", model, dimensions);
        var factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .build());
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
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
        var vectors = new ArrayList<float[]>();
        for (int offset = 0; offset < texts.size(); offset += batchSize) {
            var slice = texts.subList(offset, Math.min(offset + batchSize, texts.size()));
            vectors.addAll(embedSlice(slice));
        }
        return new EmbeddingBatch(vectors);
    }

    private List<float[]> embedSlice(List<String> slice) {
        var body = new HashMap<String, Object>();
        body.put("model", model);
        body.put("input", List.copyOf(slice));
        if (!"none".equals(dimensionField)) {
            body.put(dimensionField, dimensions);
        }
        String response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.RestClientException ex) {
            throw new EmbeddingException(
                    "EMBEDDING_PROVIDER_FAILED",
                    "embedding provider request failed");
        }
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (Exception ex) {
            throw new EmbeddingException(
                    "EMBEDDING_PROVIDER_FAILED",
                    "embedding provider returned an invalid response");
        }
        return parseResponse(root, slice.size());
    }

    private List<float[]> parseResponse(JsonNode root, int expectedCount) {
        if (root == null || !root.has("data") || !root.get("data").isArray()) {
            throw new EmbeddingException(
                    "EMBEDDING_PROVIDER_FAILED", "embedding provider returned no data");
        }
        var byIndex = new HashMap<Integer, float[]>();
        var seen = new HashSet<Integer>();
        for (JsonNode item : root.get("data")) {
            int index = item.path("index").asInt(-1);
            var embedding = item.get("embedding");
            if (index < 0 || embedding == null || !embedding.isArray()
                    || embedding.size() != dimensions) {
                throw new EmbeddingException(
                        "EMBEDDING_DIMENSION_MISMATCH",
                        "embedding provider returned an invalid vector");
            }
            if (!seen.add(index)) {
                throw new EmbeddingException(
                        "EMBEDDING_PROVIDER_FAILED",
                        "embedding provider returned duplicate indices");
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                double value = embedding.get(i).asDouble();
                if (!Double.isFinite(value)) {
                    throw new EmbeddingException(
                            "EMBEDDING_PROVIDER_FAILED",
                            "embedding provider returned a non-finite vector");
                }
                vector[i] = (float) value;
            }
            byIndex.put(index, vector);
        }
        if (byIndex.size() != expectedCount) {
            throw new EmbeddingException(
                    "EMBEDDING_PROVIDER_FAILED",
                    "embedding provider returned the wrong number of vectors");
        }
        var ordered = new ArrayList<float[]>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            var vector = byIndex.get(i);
            if (vector == null) {
                throw new EmbeddingException(
                        "EMBEDDING_PROVIDER_FAILED",
                        "embedding provider returned an out-of-range index");
            }
            ordered.add(vector);
        }
        return List.copyOf(ordered);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
