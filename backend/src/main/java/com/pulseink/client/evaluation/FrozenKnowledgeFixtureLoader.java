package com.pulseink.client.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Strict reader for versioned knowledge snapshots used by the evaluation tool runtime. */
public final class FrozenKnowledgeFixtureLoader {

    private final Path root;
    private final ObjectMapper mapper;

    public FrozenKnowledgeFixtureLoader(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public FrozenKnowledgeSnapshot load(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("knowledge fixture escapes evaluation root");
        }
        try {
            return mapper.readValue(resolved.toFile(), FrozenKnowledgeSnapshot.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid frozen knowledge fixture: " + relativePath, ex);
        }
    }

    public record FrozenKnowledgeSnapshot(
            String snapshotVersion,
            String brand,
            List<FrozenKnowledgeChunk> chunks) {
        public FrozenKnowledgeSnapshot {
            if (snapshotVersion == null || snapshotVersion.isBlank()) {
                throw new IllegalArgumentException("snapshotVersion must not be blank");
            }
            if (brand == null || brand.isBlank()) throw new IllegalArgumentException("brand required");
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    public record FrozenKnowledgeChunk(String chunkId, String text, String authority) {
        public FrozenKnowledgeChunk {
            if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("chunkId required");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("text required");
            if (authority == null || authority.isBlank()) throw new IllegalArgumentException("authority required");
        }
    }
}
