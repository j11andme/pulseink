package com.pulseink.client.search;

import com.pulseink.service.embedding.EmbeddingProfile;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Deterministic index naming: one fixed active alias and versioned physical indices derived
 * from the embedding profile fingerprint.
 */
public final class KnowledgeIndexNaming {

    public static final String SCHEMA_PREFIX = "pulseink-knowledge-v1";

    private final String alias;

    public KnowledgeIndexNaming(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        if (alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        this.alias = alias;
    }

    public String alias() {
        return alias;
    }

    public String physicalIndex(EmbeddingProfile profile) {
        String safeProfile = profile.profileId()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(profile.profileId().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        String hash = HexFormat.of().formatHex(digest).substring(0, 8);
        return SCHEMA_PREFIX + "-" + safeProfile + "-" + hash;
    }
}
