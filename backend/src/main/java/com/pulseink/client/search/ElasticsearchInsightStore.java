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
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsAliasRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.JsonData;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.domain.memory.InsightStatus;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.memory.ApprovedInsightHit;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightSearchStore;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derived Elasticsearch projection for APPROVED insights. One dedicated alias over a
 * compatible physical index, strict mapping, deterministic {@code _id}s, BM25 + KNN + Java RRF
 * with optional channel filter. A PENDING/REJECTED row is refused even when called by mistake.
 */
public final class ElasticsearchInsightStore implements InsightSearchStore {

    private static final double RRF_CONSTANT = 60.0;
    private static final int MIN_NUM_CANDIDATES = 50;

    private final ElasticsearchClient client;
    private final String alias;
    private final EmbeddingAdapter embedding;

    public ElasticsearchInsightStore(ElasticsearchClient client, String alias,
                                     EmbeddingAdapter embedding) {
        this.client = Objects.requireNonNull(client);
        this.alias = Objects.requireNonNull(alias);
        this.embedding = Objects.requireNonNull(embedding);
    }

    @Override
    public void ensureCompatibleIndex(EmbeddingProfile profile) {
        String physical = physicalIndex(profile);
        try {
            if (!aliasExists(alias)) {
                if (!indexExists(physical)) {
                    createIndex(physical, profile);
                }
                var response = client.indices().putAlias(a -> a
                        .index(physical)
                        .name(alias)
                        .isWriteIndex(true));
                if (!response.acknowledged()) {
                    throw new IllegalStateException("insight index alias creation failed");
                }
            }
            verifyAlias(profile);
        } catch (IOException | IllegalStateException ex) {
            throw new InsightException(InsightErrorCode.INSIGHT_SEARCH_UNAVAILABLE,
                    "insight index unavailable", ex);
        }
    }

    @Override
    public void indexApproved(CampaignInsight insight) {
        if (insight.status() != InsightStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "only APPROVED insights can enter the memory index");
        }
        ensureCompatibleIndex(embedding.profile());
        var source = new LinkedHashMap<String, Object>();
        source.put("insight_id", insight.id());
        source.put("title", insight.title());
        source.put("insight_text", insight.insightText());
        source.put("category", insight.category().name());
        source.put("scope_type", insight.scopeType().name());
        source.put("scope_value", insight.scopeValue());
        source.put("applicable_channels", insight.applicableChannels().stream()
                .map(Enum::name).toList());
        source.put("source_campaign_id", insight.campaignId());
        source.put("confidence", insight.confidence());
        source.put("approved_at", (insight.reviewedAt() == null
                ? insight.createdAt() : insight.reviewedAt()).toString());
        source.put("embedding", toFloats(embedding.embed(insight.insightText())));
        try {
            client.index(IndexRequest.of(i -> i
                    .index(physicalIndex(embedding.profile()))
                    .id("insight-" + insight.id())
                    .refresh(Refresh.True)
                    .document(source)));
        } catch (IOException ex) {
            throw new InsightException(InsightErrorCode.INSIGHT_SEARCH_UNAVAILABLE,
                    "insight index write failed", ex);
        }
    }

    @Override
    public List<ApprovedInsightHit> search(String query, CampaignChannel channel, int topK) {
        ensureCompatibleIndex(embedding.profile());
        String physical = physicalIndex(embedding.profile());
        BoolQuery filter = channel == null ? null : BoolQuery.of(b -> b
                .must(m -> m.term(t -> t.field("applicable_channels")
                        .value(channel.name()))));
        var lexical = lexicalSearch(physical, query, topK, filter);
        List<SearchResponse<Map<String, Object>>> knn;
        try {
            var vector = embedding.embed(query);
            knn = List.of(knnSearch(physical, query, topK, filter, vector));
        } catch (RuntimeException embeddingFailure) {
            knn = List.of();
        }
        return fuse(lexical, knn, topK);
    }

    private List<ApprovedInsightHit> fuse(
            SearchResponse<Map<String, Object>> lexical,
            List<SearchResponse<Map<String, Object>>> knn,
            int topK) {
        var scores = new LinkedHashMap<Long, Double>();
        var docs = new LinkedHashMap<Long, Map<String, Object>>();
        merge(lexical, scores, docs);
        for (var response : knn) {
            merge(response, scores, docs);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> toHit(entry.getKey(), docs.get(entry.getKey())))
                .toList();
    }

    private static void merge(SearchResponse<Map<String, Object>> response,
                              Map<Long, Double> scores,
                              Map<Long, Map<String, Object>> docs) {
        int rank = 1;
        for (var hit : response.hits().hits()) {
            var source = hit.source();
            if (source == null) {
                continue;
            }
            long insightId = longValue(source.get("insight_id"));
            scores.merge(insightId, 1.0 / (RRF_CONSTANT + rank), Double::sum);
            docs.putIfAbsent(insightId, source);
            rank++;
        }
    }

    private ApprovedInsightHit toHit(long insightId, Map<String, Object> source) {
        var channels = new ArrayList<CampaignChannel>();
        Object rawChannels = source.get("applicable_channels");
        if (rawChannels instanceof List<?> list) {
            for (Object value : list) {
                channels.add(CampaignChannel.valueOf(String.valueOf(value)));
            }
        }
        return new ApprovedInsightHit(
                insightId,
                longValue(source.get("source_campaign_id")),
                str(source.get("title")),
                str(source.get("insight_text")),
                InsightCategory.valueOf(str(source.get("category"))),
                InsightScopeType.valueOf(str(source.get("scope_type"))),
                str(source.get("scope_value")),
                List.copyOf(channels),
                doubleValue(source.get("confidence")),
                Instant.parse(str(source.get("approved_at"))));
    }

    private SearchResponse<Map<String, Object>> lexicalSearch(
            String physical, String query, int topK, BoolQuery filter) {
        var multiMatch = MultiMatchQuery.of(m -> m
                .query(query)
                .fields("title^2", "insight_text")
                .type(TextQueryType.BestFields));
        try {
            return client.search(SearchRequest.of(s -> s
                    .index(physical)
                    .size(topK)
                    .query(Query.of(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(multiMatch));
                        if (filter != null) {
                            b.filter(filter);
                        }
                        return b;
                    })))), MAP_TYPE);
        } catch (IOException ex) {
            throw new InsightException(InsightErrorCode.INSIGHT_SEARCH_UNAVAILABLE,
                    "insight index unavailable", ex);
        }
    }

    private SearchResponse<Map<String, Object>> knnSearch(
            String physical, String query, int topK, BoolQuery filter, float[] vector) {
        var knn = KnnSearch.of(k -> k
                .field("embedding")
                .queryVector(toFloats(vector))
                .k(topK)
                .numCandidates(Math.max(MIN_NUM_CANDIDATES, topK * 5))
                .filter(filter));
        try {
            return client.search(SearchRequest.of(s -> s
                    .index(physical)
                    .size(topK)
                    .knn(knn)), MAP_TYPE);
        } catch (IOException ex) {
            throw new InsightException(InsightErrorCode.INSIGHT_SEARCH_UNAVAILABLE,
                    "insight index unavailable", ex);
        }
    }

    private void createIndex(String physical, EmbeddingProfile profile) throws IOException {
        var properties = new LinkedHashMap<String, Property>();
        properties.put("insight_id", Property.of(p -> p.long_(l -> l)));
        properties.put("title", Property.of(p -> p.text(t -> t)));
        properties.put("insight_text", Property.of(p -> p.text(t -> t)));
        properties.put("category", Property.of(p -> p.keyword(k -> k)));
        properties.put("scope_type", Property.of(p -> p.keyword(k -> k)));
        properties.put("scope_value", Property.of(p -> p.keyword(k -> k)));
        properties.put("applicable_channels", Property.of(p -> p.keyword(k -> k)));
        properties.put("source_campaign_id", Property.of(p -> p.long_(l -> l)));
        properties.put("confidence", Property.of(p -> p.double_(d -> d)));
        properties.put("approved_at", Property.of(p -> p.date(d -> d)));
        properties.put("embedding", Property.of(p -> p.denseVector(d -> d
                .dims(profile.dimensions())
                .index(true)
                .similarity(co.elastic.clients.elasticsearch._types.mapping
                        .DenseVectorSimilarity.Cosine))));
        var meta = new LinkedHashMap<String, JsonData>();
        meta.put("schemaVersion", JsonData.of("v1"));
        meta.put("profileId", JsonData.of(profile.profileId()));
        meta.put("dimensions", JsonData.of(profile.dimensions()));
        client.indices().create(CreateIndexRequest.of(c -> c
                .index(physical)
                .settings(IndexSettings.of(s -> s.numberOfShards("1").numberOfReplicas("0")))
                .mappings(TypeMapping.of(m -> m
                        .dynamic(DynamicMapping.Strict)
                        .properties(properties)
                        .meta(meta)))
                .aliases(alias, a -> a.isWriteIndex(true))));
    }

    private boolean aliasExists(String alias) throws IOException {
        return client.indices().existsAlias(ExistsAliasRequest.of(e -> e.name(alias))).value();
    }

    private void verifyAlias(EmbeddingProfile profile) throws IOException {
        GetAliasResponse response = client.indices().getAlias(
                GetAliasRequest.of(g -> g.name(alias)));
        var indices = response.aliases().keySet();
        if (indices.size() != 1 || !indices.iterator().next().equals(physicalIndex(profile))) {
            throw new IllegalStateException("MEMORY_INDEX_PROFILE_MISMATCH");
        }
    }

    private boolean indexExists(String index) throws IOException {
        return client.indices().exists(ExistsRequest.of(e -> e.index(index))).value();
    }

    private String physicalIndex(EmbeddingProfile profile) {
        return alias + "-" + profile.profileId();
    }

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_TYPE =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(str(value));
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(str(value));
    }

    private static List<Float> toFloats(float[] vector) {
        var list = new ArrayList<Float>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    /**
     * Embedding access isolated for the insight store so the ES layer stays business-neutral.
     */
    public interface EmbeddingAdapter {
        EmbeddingProfile profile();

        float[] embed(String text);
    }
}
