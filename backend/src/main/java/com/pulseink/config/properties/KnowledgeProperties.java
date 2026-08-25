package com.pulseink.config.properties;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knowledge ingestion/retrieval configuration. All values are public-safe; no keys live here.
 */
@ConfigurationProperties("pulseink.knowledge")
public record KnowledgeProperties(
        Path storageRoot,
        long maxFileBytes,
        long maxExtractedCharacters,
        int maxChunkCodePoints,
        @DefaultValue("120") int chunkOverlap,
        int maxChunks,
        String indexAlias,
        int branchLimit,
        int rrfConstant,
        int snippetMaxCodePoints,
        Duration staleJobTimeout) {

    public KnowledgeProperties {
        if (storageRoot == null) {
            storageRoot = Path.of("./data/knowledge").toAbsolutePath().normalize();
        } else {
            storageRoot = storageRoot.toAbsolutePath().normalize();
        }
        if (maxFileBytes <= 0) {
            maxFileBytes = 10L * 1024 * 1024;
        }
        if (maxExtractedCharacters <= 0) {
            maxExtractedCharacters = 2_000_000L;
        }
        if (maxChunkCodePoints <= 0) {
            maxChunkCodePoints = 1000;
        }
        if (chunkOverlap < 0 || chunkOverlap >= maxChunkCodePoints) {
            throw new IllegalStateException(
                    "pulseink.knowledge.chunk-overlap must satisfy 0 <= overlap < max-chunk-code-points");
        }
        if (maxChunks <= 0) {
            maxChunks = 2000;
        }
        if (indexAlias == null || indexAlias.isBlank()) {
            indexAlias = "pulseink-knowledge-active";
        }
        if (branchLimit <= 0) {
            branchLimit = 20;
        }
        if (rrfConstant <= 0) {
            rrfConstant = 60;
        }
        if (snippetMaxCodePoints <= 0) {
            snippetMaxCodePoints = 500;
        }
        if (staleJobTimeout == null) {
            staleJobTimeout = Duration.ofMinutes(10);
        }
    }
}
