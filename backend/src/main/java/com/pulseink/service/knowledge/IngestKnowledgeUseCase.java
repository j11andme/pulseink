package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.io.InputStream;

/**
 * Upload and retry entry points for knowledge ingestion.
 */
public interface IngestKnowledgeUseCase {

    UploadResult upload(UploadCommand command);

    void retry(long documentId);

    record UploadCommand(
            String originalFilename,
            String declaredMimeType,
            KnowledgeType knowledgeType,
            EvidenceAuthority authority,
            long createdBy,
            long maxBytes,
            InputStream content) {
    }

    record UploadResult(
            long documentId,
            String sourceId,
            String jobId,
            String status) {
    }

    final class KnowledgeDocumentDuplicateException extends RuntimeException {
        public KnowledgeDocumentDuplicateException() {
            super("knowledge document already exists");
        }
    }
}
