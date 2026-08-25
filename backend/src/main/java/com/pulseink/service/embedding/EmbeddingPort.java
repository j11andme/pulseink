package com.pulseink.service.embedding;

import java.util.List;

/**
 * Embedding boundary fully separated from the chat model configuration. Embeddings are ordered,
 * finite and dimension-stable; failures are stable sanitized domain exceptions.
 */
public interface EmbeddingPort {

    EmbeddingProfile profile();

    EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose);

    /**
     * Stable, sanitized embedding failure. Never carries provider stack traces, keys or
     * Authorization headers.
     */
    final class EmbeddingException extends RuntimeException {
        private final String code;

        public EmbeddingException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
