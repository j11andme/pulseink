package com.pulseink.client.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.pulseink.client.embedding.DeterministicFakeEmbeddingAdapter;
import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.embedding.EmbeddingPurpose;
import com.pulseink.service.knowledge.EmbeddedChunk;
import com.pulseink.service.knowledge.HybridSearchQuery;
import com.pulseink.service.knowledge.IndexedDocument;
import com.pulseink.service.knowledge.RetrievalMode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

class ElasticsearchRetrievalAdapterIT {

    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.4.2")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        ELASTICSEARCH.start();
    }

    private ElasticsearchClient client;
    private ElasticsearchRetrievalAdapter adapter;
    private final EmbeddingPort embedding = new DeterministicFakeEmbeddingAdapter();
    private final String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        client = ElasticsearchClient.of(c -> c
                .host(java.net.URI.create("http://" + ELASTICSEARCH.getHttpHostAddress()))
                .jsonMapper(new JacksonJsonpMapper()));
        var naming = new KnowledgeIndexNaming("pulseink-knowledge-test-" + suffix);
        adapter = new ElasticsearchRetrievalAdapter(client, naming, new ElasticsearchRetrievalAdapter.EmbeddingPortAdapter() {
            @Override
            public String providerId() {
                return embedding.profile().providerId();
            }

            @Override
            public String modelId() {
                return embedding.profile().modelId();
            }

            @Override
            public int dimensions() {
                return embedding.profile().dimensions();
            }

            @Override
            public float[] embedQuery(String text) {
                return embedding.embed(List.of(text), EmbeddingPurpose.QUERY).vectors().get(0);
            }
        });
    }

    private EmbeddingProfile profile() {
        return embedding.profile();
    }

    @Test
    void ensureCompatibleIndexCreatesStrictMappingAndActiveAlias() throws IOException {
        adapter.ensureCompatibleIndex(profile());
        var alias = new KnowledgeIndexNaming("pulseink-knowledge-test-" + suffix).alias();
        var response = client.indices().getAlias(g -> g.name(alias));
        assertThat(response.aliases()).hasSize(1);
        String physical = response.aliases().keySet().iterator().next();

        var mapping = client.indices().getMapping(m -> m.index(physical));
        var record = mapping.mappings().get(physical);
        assertThat(record.mappings().dynamic().jsonValue()).isEqualTo("strict");
        assertThat(record.mappings().properties()).containsKeys("chunk_id", "embedding", "text");
    }

    @Test
    void profileMismatchFailsClosed() {
        adapter.ensureCompatibleIndex(profile());
        var other = EmbeddingProfile.of("fake", "other-model", 64);
        assertThatThrownBy(() -> adapter.ensureCompatibleIndex(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KNOWLEDGE_INDEX_PROFILE_MISMATCH");
    }

    @Test
    void replaceAndSearchChineseAndEnglishHybrid() {
        adapter.ensureCompatibleIndex(profile());
        var document = new IndexedDocument(
                1L, 1, "src-1", "PulseInk 品牌指南",
                KnowledgeType.BRAND_GUIDELINE, EvidenceAuthority.OFFICIAL, Instant.now());
        adapter.replaceDocumentVersion(document, chunks(document));

        var query = new HybridSearchQuery(
                "品牌颜色", List.of(), List.of(), null, 5, 10, profile());
        var candidates = adapter.search(query);

        assertThat(candidates.mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(candidates.lexical()).isNotEmpty();
        assertThat(candidates.vector()).isNotEmpty();

        var englishQuery = new HybridSearchQuery(
                "brand color", List.of(), List.of(), null, 5, 10, profile());
        var english = adapter.search(englishQuery);
        assertThat(english.lexical()).isNotEmpty();
    }

    @Test
    void replaceDocumentVersionOverwritesDeterministically() {
        adapter.ensureCompatibleIndex(profile());
        var document = new IndexedDocument(
                1L, 1, "src-1", "Title", KnowledgeType.PRODUCT,
                EvidenceAuthority.OFFICIAL, Instant.now());
        adapter.replaceDocumentVersion(document, chunks(document));
        adapter.replaceDocumentVersion(document, chunks(document));

        var query = new HybridSearchQuery("brand", List.of(), List.of(), null, 5, 10, profile());
        var candidates = adapter.search(query);
        assertThat(candidates.vector()).hasSize(chunks(document).size());
    }

    @Test
    void filtersApplyToBothBranches() {
        adapter.ensureCompatibleIndex(profile());
        var document = new IndexedDocument(
                1L, 1, "src-1", "Guide", KnowledgeType.BRAND_GUIDELINE,
                EvidenceAuthority.OFFICIAL, Instant.now());
        adapter.replaceDocumentVersion(document, chunks(document));

        var filtered = new HybridSearchQuery(
                "brand", List.of(KnowledgeType.CHANNEL_RULE), List.of(), null, 5, 10, profile());
        var candidates = adapter.search(filtered);
        assertThat(candidates.lexical()).isEmpty();
        assertThat(candidates.vector()).isEmpty();

        var matching = new HybridSearchQuery(
                "brand", List.of(KnowledgeType.BRAND_GUIDELINE), List.of(), null, 5, 10, profile());
        assertThat(adapter.search(matching).lexical()).isNotEmpty();
    }

    @Test
    void fewerChunksAreCleanedUpOnReplace() {
        adapter.ensureCompatibleIndex(profile());
        var document = new IndexedDocument(
                1L, 1, "src-1", "Title", KnowledgeType.PRODUCT,
                EvidenceAuthority.OFFICIAL, Instant.now());
        adapter.replaceDocumentVersion(document, chunks(document));
        adapter.replaceDocumentVersion(document, List.of(chunks(document).get(0)));

        var query = new HybridSearchQuery("brand", List.of(), List.of(), null, 5, 10, profile());
        assertThat(adapter.search(query).lexical()).hasSize(1);
    }

    @Test
    void deleteDocumentVersionRemovesOnlyThatVersion() {
        adapter.ensureCompatibleIndex(profile());
        var v1 = new IndexedDocument(
                1L, 1, "src-1", "Old", KnowledgeType.PRODUCT,
                EvidenceAuthority.OFFICIAL, Instant.now());
        var v2 = new IndexedDocument(
                1L, 2, "src-1", "New", KnowledgeType.PRODUCT,
                EvidenceAuthority.OFFICIAL, Instant.now());
        adapter.replaceDocumentVersion(v1, chunks(v1));
        adapter.replaceDocumentVersion(v2, chunks(v2));

        adapter.deleteDocumentVersion(1L, 1);

        var query = new HybridSearchQuery("brand", List.of(), List.of(), null, 5, 10, profile());
        var candidates = adapter.search(query);
        assertThat(candidates.vector()).hasSize(chunks(v2).size());
        assertThat(candidates.vector())
                .allSatisfy(candidate -> assertThat(candidate.documentVersion()).isEqualTo(2));
    }

    @Test
    void connectionFailureIsNotDegradedToEmpty() {
        var deadAdapter = new ElasticsearchRetrievalAdapter(
                ElasticsearchClient.of(c -> c
                        .host("http://localhost:59999")
                        .jsonMapper(new JacksonJsonpMapper())),
                new KnowledgeIndexNaming("pulseink-knowledge-dead-" + suffix),
                new ElasticsearchRetrievalAdapter.EmbeddingPortAdapter() {
                    @Override
                    public String providerId() {
                        return "fake";
                    }

                    @Override
                    public String modelId() {
                        return "m";
                    }

                    @Override
                    public int dimensions() {
                        return 64;
                    }

                    @Override
                    public float[] embedQuery(String text) {
                        return new float[64];
                    }
                });
        var query = new HybridSearchQuery("x", List.of(), List.of(), null, 5, 10, profile());
        assertThatThrownBy(() -> deadAdapter.search(query))
                .isInstanceOf(IllegalStateException.class);
    }

    private List<EmbeddedChunk> chunks(IndexedDocument document) {
        var chunks = new ArrayList<EmbeddedChunk>();
        int ordinal = 0;
        for (String text : List.of(
                "PulseInk 品牌颜色是蓝色，用于主视觉。",
                "The brand color is blue and used in the primary visual.",
                "品牌 logo 需要保持四周留白。")) {
            var vector = embedding.embed(List.of(text), EmbeddingPurpose.INDEX).vectors().get(0);
            chunks.add(new EmbeddedChunk(
                    document.documentId() + ":" + document.documentVersion() + ":" + ordinal,
                    document.documentId(), document.documentVersion(), document.sourceId(),
                    ordinal, document.title(), "Guide > " + ordinal, text,
                    document.knowledgeType(), document.authority(), "ACTIVE",
                    profile().profileId(), document.updatedAt(), vector));
            ordinal++;
        }
        return List.copyOf(chunks);
    }

    @AfterAll
    static void stopElasticsearch() {
        ELASTICSEARCH.stop();
    }
}
