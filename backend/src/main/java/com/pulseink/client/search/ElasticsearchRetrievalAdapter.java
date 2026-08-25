package com.pulseink.client.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsAliasRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.JsonData;
import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.knowledge.EmbeddedChunk;
import com.pulseink.service.knowledge.HybridSearchQuery;
import com.pulseink.service.knowledge.IndexedDocument;
import com.pulseink.service.knowledge.RetrievalCandidates;
import com.pulseink.service.knowledge.RetrievalMode;
import com.pulseink.service.knowledge.RetrievalStore;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Elasticsearch retrieval adapter. Strict mapping, one active alias over a compatible physical
 * index, deterministic {@code _id}s and a shared pre-filter for BM25 and KNN branches. Query
 * embedding failures degrade to BM25-only with LEXICAL_FALLBACK; ES availability errors never
 * masquerade as empty results.
 */
public final class ElasticsearchRetrievalAdapter implements RetrievalStore {

    private final ElasticsearchClient client;
    private final KnowledgeIndexNaming naming;
    private final EmbeddingPortAdapter embedding;

    public ElasticsearchRetrievalAdapter(ElasticsearchClient client,
                                         KnowledgeIndexNaming naming,
                                         EmbeddingPortAdapter embedding) {
        this.client = Objects.requireNonNull(client);
        this.naming = Objects.requireNonNull(naming);
        this.embedding = Objects.requireNonNull(embedding);
    }

    @Override
    public void ensureCompatibleIndex(EmbeddingProfile profile) {
        String alias = naming.alias();
        String physical = naming.physicalIndex(profile);
        try {
            if (!aliasExists(alias)) {
                if (!indexExists(physical)) {
                    createIndex(physical, alias, profile);
                }
                var response = client.indices().putAlias(a -> a
                        .index(physical)
                        .name(alias)
                        .isWriteIndex(true));
                if (!response.acknowledged()) {
                    throw new IllegalStateException("knowledge index alias creation failed");
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge index unavailable", ex);
        }
        verifyAlias(alias, profile);
    }

    @Override
    public String physicalIndexName(EmbeddingProfile profile) {
        return naming.physicalIndex(profile);
    }

    @Override
    public void replaceDocumentVersion(IndexedDocument document, List<EmbeddedChunk> chunks) {
        String physical = naming.physicalIndex(currentProfile());
        try {
            client.deleteByQuery(DeleteByQueryRequest.of(d -> d
                    .index(physical)
                    .refresh(true)
                    .query(Query.of(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("document_id").value(document.documentId())))
                            .must(m -> m.term(t -> t.field("document_version")
                                    .value(document.documentVersion()))))))));
            var operations = new ArrayList<BulkOperation>();
            for (var chunk : chunks) {
                var source = new LinkedHashMap<String, Object>();
                source.put("chunk_id", chunk.chunkId());
                source.put("document_id", chunk.documentId());
                source.put("source_id", chunk.sourceId());
                source.put("document_version", chunk.documentVersion());
                source.put("ordinal", chunk.ordinal());
                source.put("title", chunk.title());
                source.put("heading_path", chunk.headingPath());
                source.put("text", chunk.text());
                source.put("knowledge_type", chunk.knowledgeType().name());
                source.put("authority", chunk.authority().name());
                source.put("document_status", chunk.documentStatus());
                source.put("embedding_profile_id", chunk.embeddingProfileId());
                source.put("updated_at", chunk.updatedAt().toString());
                source.put("embedding", chunk.vectorCopy());
                operations.add(new BulkOperation.Builder()
                        .index(IndexOperation.of(i -> i
                                .index(physical)
                                .id(chunk.chunkId())
                                .document(source)))
                        .build());
            }
            if (!operations.isEmpty()) {
                BulkResponse bulk = client.bulk(BulkRequest.of(b -> b
                        .index(physical)
                        .refresh(Refresh.True)
                        .operations(operations)));
                if (bulk.errors()) {
                    throw new IllegalStateException("knowledge index bulk write failed");
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge index unavailable", ex);
        }
    }

    @Override
    public RetrievalCandidates search(HybridSearchQuery query) {
        verifyAlias(naming.alias(), query.profile());
        String physical = naming.physicalIndex(query.profile());
        var filters = sharedFilter(query);
        float[] queryVector;
        try {
            queryVector = embedding.embedQuery(query.query());
        } catch (RuntimeException ex) {
            try {
                return new RetrievalCandidates(
                        RetrievalMode.LEXICAL_FALLBACK, "EMBEDDING_PROVIDER_FAILED",
                        lexicalSearch(physical, query, filters), List.of());
            } catch (IOException io) {
                throw new IllegalStateException("knowledge index unavailable", io);
            }
        }
        try {
            var lexical = lexicalSearch(physical, query, filters);
            var vector = knnSearch(physical, query, filters, queryVector);
            return new RetrievalCandidates(RetrievalMode.HYBRID, null, lexical, vector);
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge index unavailable", ex);
        }
    }

    @Override
    public void deleteDocumentVersion(long documentId, int documentVersion) {
        String physical = naming.physicalIndex(currentProfile());
        try {
            client.deleteByQuery(DeleteByQueryRequest.of(d -> d
                    .index(physical)
                    .refresh(true)
                    .query(Query.of(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("document_id").value(documentId)))
                            .must(m -> m.term(t -> t.field("document_version").value(documentVersion))))))));
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge index unavailable", ex);
        }
    }

    private List<RetrievalCandidates.Candidate> lexicalSearch(
            String physical, HybridSearchQuery query, BoolQuery filters) throws IOException {
        var multiMatch = MultiMatchQuery.of(m -> m
                .query(query.query())
                .fields("title^2", "heading_path^1.5", "text")
                .type(TextQueryType.BestFields));
        var request = SearchRequest.of(s -> s
                .index(physical)
                .size(query.branchLimit())
                .query(Query.of(q -> q.bool(b -> b
                        .must(m -> m.multiMatch(multiMatch))
                        .filter(filters)))));
        SearchResponse<Map<String, Object>> response = client.search(request, MAP_TYPE);
        return toCandidates(response);
    }

    private List<RetrievalCandidates.Candidate> knnSearch(
            String physical, HybridSearchQuery query, BoolQuery filters,
            float[] queryVector) throws IOException {
        var knn = KnnSearch.of(k -> k
                .field("embedding")
                .queryVector(toFloats(queryVector))
                .k(query.branchLimit())
                .numCandidates(Math.min(10_000, Math.max(50, query.branchLimit() * 5)))
                .filter(filters));
        var request = SearchRequest.of(s -> s
                .index(physical)
                .size(query.branchLimit())
                .knn(knn));
        SearchResponse<Map<String, Object>> response = client.search(request, MAP_TYPE);
        return toCandidates(response);
    }

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_TYPE =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    private static List<RetrievalCandidates.Candidate> toCandidates(
            SearchResponse<Map<String, Object>> response) {
        var candidates = new ArrayList<RetrievalCandidates.Candidate>();
        for (var hit : response.hits().hits()) {
            var source = hit.source();
            if (source == null) {
                continue;
            }
            candidates.add(new RetrievalCandidates.Candidate(
                    str(source.get("chunk_id")),
                    longValue(source.get("document_id")),
                    intValue(source.get("document_version")),
                    hit.score() == null ? 0.0 : hit.score(),
                    str(source.get("title")),
                    str(source.get("heading_path")),
                    str(source.get("text")),
                    str(source.get("source_id")),
                    KnowledgeType.valueOf(str(source.get("knowledge_type"))),
                    EvidenceAuthority.valueOf(str(source.get("authority"))),
                    Instant.parse(str(source.get("updated_at")))));
        }
        return List.copyOf(candidates);
    }

    private static BoolQuery sharedFilter(HybridSearchQuery query) {
        var builder = new BoolQuery.Builder();
        builder.must(m -> m.term(t -> t.field("document_status").value("ACTIVE")));
        builder.must(m -> m.term(t -> t.field("embedding_profile_id")
                .value(query.profile().profileId())));
        if (!query.knowledgeTypes().isEmpty()) {
            builder.must(m -> m.terms(t -> t.field("knowledge_type")
                    .terms(TermsQueryField.of(f -> f.value(
                            toFieldValues(query.knowledgeTypes()))))));
        }
        if (!query.authorities().isEmpty()) {
            builder.must(m -> m.terms(t -> t.field("authority")
                    .terms(TermsQueryField.of(f -> f.value(
                            toFieldValues(query.authorities()))))));
        }
        if (query.updatedAfter() != null) {
            builder.must(m -> m.range(r -> r
                    .date(d -> d.field("updated_at").gte(query.updatedAfter().toString()))));
        }
        return builder.build();
    }

    private static <E extends Enum<E>> List<co.elastic.clients.elasticsearch._types.FieldValue> toFieldValues(
            List<E> values) {
        var list = new ArrayList<co.elastic.clients.elasticsearch._types.FieldValue>(values.size());
        for (E value : values) {
            list.add(co.elastic.clients.elasticsearch._types.FieldValue.of(value.name()));
        }
        return list;
    }

    private void verifyAlias(String alias, EmbeddingProfile profile) {
        try {
            if (!aliasExists(alias)) {
                throw new IllegalStateException("knowledge index alias is missing");
            }
            GetAliasResponse response = client.indices().getAlias(
                    GetAliasRequest.of(g -> g.name(alias)));
            var indices = response.aliases().keySet();
            if (indices.size() != 1) {
                throw new IllegalStateException(
                        "knowledge index alias must point to exactly one index");
            }
            String physical = indices.iterator().next();
            if (!physical.equals(naming.physicalIndex(profile))) {
                throw new IllegalStateException("KNOWLEDGE_INDEX_PROFILE_MISMATCH");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge index unavailable", ex);
        }
    }

    private boolean aliasExists(String alias) throws IOException {
        return client.indices().existsAlias(ExistsAliasRequest.of(e -> e.name(alias))).value();
    }

    private boolean indexExists(String index) throws IOException {
        return client.indices().exists(ExistsRequest.of(e -> e.index(index))).value();
    }

    private void createIndex(String physical, String alias, EmbeddingProfile profile)
            throws IOException {
        var properties = new LinkedHashMap<String, Property>();
        properties.put("chunk_id", Property.of(p -> p.keyword(k -> k)));
        properties.put("document_id", Property.of(p -> p.long_(l -> l)));
        properties.put("source_id", Property.of(p -> p.keyword(k -> k)));
        properties.put("document_version", Property.of(p -> p.integer(i -> i)));
        properties.put("ordinal", Property.of(p -> p.integer(i -> i)));
        properties.put("title", Property.of(p -> p.text(t -> t)));
        properties.put("heading_path", Property.of(p -> p.text(t -> t)));
        properties.put("text", Property.of(p -> p.text(t -> t)));
        properties.put("knowledge_type", Property.of(p -> p.keyword(k -> k)));
        properties.put("authority", Property.of(p -> p.keyword(k -> k)));
        properties.put("document_status", Property.of(p -> p.keyword(k -> k)));
        properties.put("embedding_profile_id", Property.of(p -> p.keyword(k -> k)));
        properties.put("updated_at", Property.of(p -> p.date(d -> d)));
        properties.put("embedding", Property.of(p -> p.denseVector(d -> d
                .dims(profile.dimensions())
                .index(true)
                .similarity(co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity.Cosine))));
        var meta = new LinkedHashMap<String, JsonData>();
        meta.put("schemaVersion", JsonData.of("v1"));
        meta.put("profileId", JsonData.of(profile.profileId()));
        meta.put("modelId", JsonData.of(profile.modelId()));
        meta.put("dimensions", JsonData.of(profile.dimensions()));
        meta.put("similarity", JsonData.of("cosine"));
        client.indices().create(CreateIndexRequest.of(c -> c
                .index(physical)
                .settings(IndexSettings.of(s -> s.numberOfShards("1").numberOfReplicas("0")))
                .mappings(TypeMapping.of(m -> m
                        .dynamic(DynamicMapping.Strict)
                        .properties(properties)
                        .meta(meta)))
                .aliases(alias, a -> a.isWriteIndex(true))));
    }

    private EmbeddingProfile currentProfile() {
        return EmbeddingProfile.of(embedding.providerId(), embedding.modelId(),
                embedding.dimensions());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(str(value));
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(str(value));
    }

    private static List<Float> toFloats(float[] vector) {
        var list = new ArrayList<Float>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    /**
     * Embedding access isolated for the retrieval adapter so the ES layer stays business-neutral.
     */
    public interface EmbeddingPortAdapter {
        String providerId();

        String modelId();

        int dimensions();

        float[] embedQuery(String text);
    }
}
