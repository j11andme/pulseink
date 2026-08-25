package com.pulseink.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.ModelInsightCandidateGenerator;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightIndexStatus;
import com.pulseink.domain.memory.InsightStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConsolidateInsightServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    private InMemoryInsightRepository insightRepository;
    private RecordingGenerator recordingGenerator;
    private ConsolidateInsightService service;
    private ModelRouter router;

    @BeforeEach
    void setUp() {
        insightRepository = new InMemoryInsightRepository();
        recordingGenerator = new RecordingGenerator();
        service = new ConsolidateInsightService(
                new FakeSourceRepository(),
                insightRepository,
                recordingGenerator,
                new TransactionTemplate(new NoopTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validGenerationCreatesPendingCandidateNotIndexed() {
        recordingGenerator.next = generated();

        var created = service.generateCandidate(2L, 3L);

        assertThat(created.id()).isPositive();
        assertThat(created.status()).isEqualTo(InsightStatus.PENDING);
        assertThat(created.indexStatus()).isEqualTo(InsightIndexStatus.NOT_INDEXED);
        assertThat(created.sourceSnapshotHash()).isEqualTo(HASH);
        assertThat(created.promptVersion()).isEqualTo(ConsolidateInsightService.PROMPT_VERSION);
        assertThat(created.createdBy()).isEqualTo(3L);
        assertThat(insightRepository.rows).hasSize(1);
    }

    @Test
    void modelCallHappensOutsideAnyDatabaseTransaction() {
        recordingGenerator.next = generated();

        service.generateCandidate(2L, 3L);

        assertThat(recordingGenerator.transactionActiveAtCall).isFalse();
    }

    @Test
    void sameSnapshotReplayReturnsExistingCandidateWithoutAnotherModelCall() {
        recordingGenerator.next = generated();
        var first = service.generateCandidate(2L, 3L);
        var second = service.generateCandidate(2L, 3L);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(recordingGenerator.calls.get()).isEqualTo(1);
    }

    @Test
    void concurrentInsertConflictConvergesOnTheExistingRow() {
        recordingGenerator.next = generated();
        insightRepository.failNextInsert = true;

        var created = service.generateCandidate(2L, 3L);

        assertThat(created.id()).isPositive();
        assertThat(insightRepository.rows).hasSize(1);
    }

    @Test
    void sourceNotReadyPropagatesWithoutModelCall() {
        var failing = new ConsolidateInsightService(
                new MemorySourceRepository() {
                    @Override
                    public InsightSourceSnapshot loadEligibleSnapshot(long runId) {
                        throw new InsightException(InsightErrorCode.INSIGHT_SOURCE_NOT_READY,
                                "not ready");
                    }

                    @Override
                    public CampaignEpisodicMemory loadEpisode(long runId) {
                        return CampaignEpisodicMemory.empty(runId);
                    }
                },
                insightRepository,
                recordingGenerator,
                new TransactionTemplate(new NoopTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> failing.generateCandidate(2L, 3L))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_SOURCE_NOT_READY));
        assertThat(recordingGenerator.calls.get()).isZero();
        assertThat(insightRepository.rows).isEmpty();
    }

    @Test
    void firstInvalidOutputIsRepairedOnceThroughTheRealGenerator() {
        var modelPort = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("```json\nnot-json"),
                FakeModelAdapter.Scene.of(validJson())));
        router = new ModelRouter(List.of(new ModelRoute(
                "fake", "pulseink-fake", Set.of(), modelPort)));
        var generator = generator(router);
        service = new ConsolidateInsightService(
                new FakeSourceRepository(), insightRepository, generator,
                new TransactionTemplate(new NoopTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var created = service.generateCandidate(2L, 3L);

        assertThat(created.status()).isEqualTo(InsightStatus.PENDING);
        assertThat(insightRepository.rows).hasSize(1);
    }

    @Test
    void defaultFakeModelProducesAValidCandidateBoundToTheSourceSnapshot() {
        router = new ModelRouter(List.of(new ModelRoute(
                "fake", "pulseink-fake", Set.of(), new FakeModelAdapter())));
        service = new ConsolidateInsightService(
                new FakeSourceRepository(), insightRepository, generator(router),
                new TransactionTemplate(new NoopTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var created = service.generateCandidate(2L, 3L);

        assertThat(created.status()).isEqualTo(InsightStatus.PENDING);
        assertThat(created.applicableChannels()).containsExactly(CampaignChannel.SOCIAL);
        assertThat(created.evidenceRefs()).singleElement().satisfies(evidence -> {
            assertThat(evidence.contentVersionId()).isEqualTo(11L);
            assertThat(evidence.publicationId()).isEqualTo(21L);
            assertThat(evidence.metricFrom()).isEqualTo(LocalDate.of(2026, 8, 6));
            assertThat(evidence.metricTo()).isEqualTo(LocalDate.of(2026, 8, 7));
        });
    }

    @Test
    void twoInvalidOutputsMapToModelOutputInvalidWithoutRows() {
        var modelPort = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("still not json"),
                FakeModelAdapter.Scene.of("also not json")));
        router = new ModelRouter(List.of(new ModelRoute(
                "fake", "pulseink-fake", Set.of(), modelPort)));
        var generator = generator(router);
        service = new ConsolidateInsightService(
                new FakeSourceRepository(), insightRepository, generator,
                new TransactionTemplate(new NoopTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.generateCandidate(2L, 3L))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_MODEL_OUTPUT_INVALID));
        assertThat(insightRepository.rows).isEmpty();
    }

    @Test
    void providerFailureMapsToModelFailureWithoutRows() {
        var modelPort = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("PROVIDER_DOWN", "timeout")));
        router = new ModelRouter(List.of(new ModelRoute(
                "fake", "pulseink-fake", Set.of(), modelPort)));
        var generator = generator(router);
        service = new ConsolidateInsightService(
                new FakeSourceRepository(), insightRepository, generator,
                new TransactionTemplate(new NoopTransactionManager()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.generateCandidate(2L, 3L))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_MODEL_FAILURE));
        assertThat(insightRepository.rows).isEmpty();
    }

    private static ModelInsightCandidateGenerator generator(ModelRouter router) {
        return new ModelInsightCandidateGenerator(
                router,
                new ModelPolicy(List.of("fake"), Set.of()),
                4_096,
                Duration.ofSeconds(30),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    }

    private static GeneratedInsight generated() {
        return new GeneratedInsight(1,
                com.pulseink.domain.memory.InsightCategory.CHANNEL_PATTERN,
                "标题", "正文结论", com.pulseink.domain.memory.InsightScopeType.CHANNEL,
                "SOCIAL", List.of(CampaignChannel.SOCIAL),
                List.of(new com.pulseink.domain.memory.InsightEvidenceRef(11L, 21L,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7))),
                0.78, List.of("样本窗口较短"));
    }

    private static String validJson() {
        return """
                {"schemaVersion":1,"category":"CHANNEL_PATTERN",
                 "title":"社交渠道短句更有效","insightText":"短句形式能提升互动",
                 "scopeType":"CHANNEL","scopeValue":"SOCIAL",
                 "applicableChannels":["SOCIAL"],
                 "evidenceRefs":[{"contentVersionId":11,"publicationId":21,
                                  "metricFrom":"2026-08-06","metricTo":"2026-08-07"}],
                 "confidence":0.78,"limitations":["样本窗口较短"]}
                """;
    }

    private static final class FakeSourceRepository implements MemorySourceRepository {

        @Override
        public InsightSourceSnapshot loadEligibleSnapshot(long runId) {
            return new InsightSourceSnapshot(
                    runId, 1L,
                    List.of(new InsightSourceSnapshot.ApprovedVersion(11L, 1,
                            Map.of("title", "T", "body", "B"))),
                    List.of(new InsightSourceSnapshot.PublishedPost(21L, 11L,
                            CampaignChannel.SOCIAL, UUID.randomUUID(), NOW)),
                    List.of(new InsightSourceSnapshot.MetricWindow(21L,
                            LocalDate.of(2026, 8, 6), 50, 5, 2),
                            new InsightSourceSnapshot.MetricWindow(21L,
                                    LocalDate.of(2026, 8, 7), 60, 6, 3)),
                    HASH);
        }

        @Override
        public CampaignEpisodicMemory loadEpisode(long runId) {
            return CampaignEpisodicMemory.empty(runId);
        }
    }

    private static final class RecordingGenerator implements InsightCandidateGenerator {

        GeneratedInsight next;
        final AtomicInteger calls = new AtomicInteger();
        boolean transactionActiveAtCall = true;

        @Override
        public GeneratedInsight generate(InsightSourceSnapshot source) {
            calls.incrementAndGet();
            transactionActiveAtCall = TransactionSynchronizationManager.isActualTransactionActive();
            return next;
        }
    }

    private static final class InMemoryInsightRepository implements CampaignInsightRepository {

        final Map<Long, CampaignInsight> rows = new LinkedHashMap<>();
        boolean failNextInsert;
        private long nextId = 1;

        @Override
        public Optional<CampaignInsight> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<CampaignInsight> findBySnapshot(long runId, String snapshotHash,
                                                        String promptVersion) {
            return rows.values().stream()
                    .filter(insight -> insight.runId() == runId
                            && insight.sourceSnapshotHash().equals(snapshotHash)
                            && insight.promptVersion().equals(promptVersion))
                    .findFirst();
        }

        @Override
        public List<CampaignInsight> findByCampaign(long campaignId) {
            return rows.values().stream()
                    .filter(insight -> insight.campaignId() == campaignId).toList();
        }

        @Override
        public CampaignInsight insertPending(CampaignInsight candidate) {
            if (failNextInsert) {
                failNextInsert = false;
                // Simulate a concurrent winner's committed row, then fail our insert.
                var winner = stored(candidate);
                rows.put(winner.id(), winner);
                throw new DuplicateKeyException("duplicate snapshot");
            }
            var stored = stored(candidate);
            rows.put(stored.id(), stored);
            return stored;
        }

        private CampaignInsight stored(CampaignInsight candidate) {
            return new CampaignInsight(nextId++, candidate.campaignId(),
                    candidate.runId(), candidate.category(), candidate.title(),
                    candidate.insightText(), candidate.scopeType(), candidate.scopeValue(),
                    candidate.applicableChannels(), candidate.evidenceRefs(),
                    candidate.confidence(), candidate.limitations(),
                    candidate.sourceSnapshotHash(), candidate.promptVersion(),
                    candidate.status(), candidate.indexStatus(), candidate.indexAttempts(),
                    candidate.nextIndexAttemptAt(), candidate.lastIndexError(),
                    candidate.createdBy(), candidate.reviewedBy(), candidate.reviewComment(),
                    candidate.version(), candidate.createdAt(), candidate.reviewedAt(),
                    candidate.indexedAt());
        }

        @Override
        public CampaignInsight decidePending(long id, long expectedVersion,
                                             com.pulseink.domain.memory.InsightStatus targetStatus,
                                             String comment, long actorId, Instant reviewedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CampaignInsight> claimIndexDue(Instant now, int batchSize) {
            return List.of();
        }

        @Override
        public boolean markIndexed(long id, long expectedVersion, Instant indexedAt) {
            return false;
        }

        @Override
        public boolean markIndexRetry(long id, long expectedVersion,
                                      Instant nextAttemptAt, String error) {
            return false;
        }

        @Override
        public boolean markIndexFailed(long id, long expectedVersion, String error) {
            return false;
        }
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
