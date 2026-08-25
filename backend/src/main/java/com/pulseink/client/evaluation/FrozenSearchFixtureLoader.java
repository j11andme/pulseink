package com.pulseink.client.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Reads versioned search rankings used by offline regression; it never calls a live index. */
public final class FrozenSearchFixtureLoader {

    private final Path root;
    private final ObjectMapper mapper;

    public FrozenSearchFixtureLoader(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public FrozenSearchFixture load(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("search fixture escapes evaluation root");
        }
        try {
            return mapper.readValue(resolved.toFile(), FrozenSearchFixture.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid frozen search fixture: " + relativePath, ex);
        }
    }

    public record FrozenSearchFixture(String fixtureVersion, List<String> rankedChunkIds) {
        public FrozenSearchFixture {
            if (fixtureVersion == null || fixtureVersion.isBlank()) {
                throw new IllegalArgumentException("fixtureVersion must not be blank");
            }
            rankedChunkIds = rankedChunkIds == null ? List.of() : List.copyOf(rankedChunkIds);
        }
    }
}
