package com.pulseink.repository.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.KnowledgeDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper mapper;

    public MybatisKnowledgeDocumentRepository(KnowledgeDocumentMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public KnowledgeDocument insert(KnowledgeDocument document) {
        var entity = toEntity(document);
        mapper.insert(entity);
        var persisted = mapper.selectById(entity.getId());
        if (persisted == null) {
            throw new IllegalStateException(
                    "knowledge document insert did not produce a readable row");
        }
        return toDomain(persisted);
    }

    @Override
    public Optional<KnowledgeDocument> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<KnowledgeDocument> findByChecksumAndType(String checksumSha256,
                                                             KnowledgeType knowledgeType) {
        return Optional.ofNullable(mapper.findByChecksumAndType(
                        checksumSha256, knowledgeType.name()))
                .map(this::toDomain);
    }

    @Override
    public void update(KnowledgeDocument document) {
        int affected = mapper.updateStateCas(
                document.id(),
                document.status().name(),
                document.failureCode(),
                document.detectedMimeType(),
                document.embeddingProfileId(),
                document.indexName(),
                document.chunkCount(),
                document.version());
        if (affected != 1) {
            throw new IllegalStateException(
                    "stale knowledge document update for id " + document.id()
                            + ": expected version " + document.version());
        }
    }

    @Override
    public void markProcessing(long id) {
        var document = requireDocument(id);
        document.markProcessing();
        update(document);
    }

    @Override
    public void markActive(long id, String detectedMimeType, String embeddingProfileId,
                           String indexName, int chunkCount) {
        var document = requireDocument(id);
        document.markActive(detectedMimeType, embeddingProfileId, indexName, chunkCount);
        update(document);
    }

    @Override
    public void markFailed(long id, String failureCode) {
        var document = requireDocument(id);
        document.markFailed(failureCode);
        update(document);
    }

    @Override
    public void retry(long id) {
        var document = requireDocument(id);
        document.retry();
        update(document);
    }

    @Override
    public DocumentPage findPage(KnowledgeDocumentStatus status, KnowledgeType type,
                                 int page, int size) {
        int offset = page * size;
        String statusName = status == null ? null : status.name();
        String typeName = type == null ? null : type.name();
        var entities = mapper.findPage(statusName, typeName, offset, size);
        var items = new ArrayList<KnowledgeDocument>();
        for (var entity : entities) {
            items.add(toDomain(entity));
        }
        return new DocumentPage(mapper.countPage(statusName, typeName), List.copyOf(items));
    }

    @Override
    public List<KnowledgeDocument> findActiveByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        var entities = mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeDocumentEntity>()
                        .in("id", ids)
                        .eq("status", KnowledgeDocumentStatus.ACTIVE.name()));
        var items = new ArrayList<KnowledgeDocument>();
        for (var entity : entities) {
            items.add(toDomain(entity));
        }
        return List.copyOf(items);
    }

    private KnowledgeDocument requireDocument(long id) {
        return findById(id).orElseThrow(() ->
                new IllegalArgumentException("knowledge document " + id + " was not found"));
    }

    private KnowledgeDocumentEntity toEntity(KnowledgeDocument document) {
        var entity = new KnowledgeDocumentEntity();
        entity.setId(document.id());
        entity.setSourceId(document.sourceId());
        entity.setOriginalFilename(document.originalFilename());
        entity.setStorageKey(document.storageKey());
        entity.setDeclaredMimeType(document.declaredMimeType());
        entity.setDetectedMimeType(document.detectedMimeType());
        entity.setSizeBytes(document.sizeBytes());
        entity.setChecksumSha256(document.checksumSha256());
        entity.setKnowledgeType(document.knowledgeType().name());
        entity.setAuthority(document.authority().name());
        entity.setDocumentVersion(document.documentVersion());
        entity.setStatus(document.status().name());
        entity.setEmbeddingProfileId(document.embeddingProfileId());
        entity.setIndexName(document.indexName());
        entity.setChunkCount(document.chunkCount());
        entity.setFailureCode(document.failureCode());
        entity.setCreatedBy(document.createdBy());
        entity.setVersion(document.version());
        return entity;
    }

    private KnowledgeDocument toDomain(KnowledgeDocumentEntity entity) {
        return KnowledgeDocument.materialize(
                entity.getId(),
                entity.getSourceId(),
                entity.getOriginalFilename(),
                entity.getStorageKey(),
                entity.getDeclaredMimeType(),
                entity.getDetectedMimeType(),
                entity.getSizeBytes() == null ? 0L : entity.getSizeBytes(),
                entity.getChecksumSha256(),
                enumValue(entity.getKnowledgeType(), KnowledgeType.class, "knowledgeType"),
                enumValue(entity.getAuthority(), EvidenceAuthority.class, "authority"),
                entity.getDocumentVersion() == null ? 1 : entity.getDocumentVersion(),
                enumValue(entity.getStatus(), KnowledgeDocumentStatus.class, "status"),
                entity.getEmbeddingProfileId(),
                entity.getIndexName(),
                entity.getChunkCount() == null ? 0 : entity.getChunkCount(),
                entity.getFailureCode(),
                entity.getCreatedBy() == null ? 0L : entity.getCreatedBy(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("stored unknown " + label + " value: " + value, ex);
        }
    }
}
