package com.pulseink.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.config.properties.PublicationProperties;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.content.ApprovalRecord;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentOrigin;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.publication.Publication;
import com.pulseink.domain.publication.PublicationStatus;
import com.pulseink.domain.publication.PublishReceipt;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.content.ContentWorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CampaignChannel CHANNEL = CampaignChannel.BLOG;

    private InMemoryPublicationRepository publications;
    private RunRepositoryStub runs;
    private ContentWorkflowRepositoryStub content;
    private RecordingChannelPort channel;
    private TransactionTemplate transactions;
    private PublicationService service;
    private PublicationWorker worker;

    @BeforeEach
    void setUp() {
        publications = new InMemoryPublicationRepository();
        runs = new RunRepositoryStub();
        content = new ContentWorkflowRepositoryStub();
        channel = new RecordingChannelPort();
        transactions = new TransactionTemplate(new NoopTransactionManager());
        service = new PublicationService(publications, content, runs, transactions, CLOCK);
        var properties = new PublicationProperties(false, java.time.Duration.ofSeconds(1),
                20, 3, java.time.Duration.ofSeconds(5));
        worker = new PublicationWorker(publications, content, runs, channel,
                properties, transactions, CLOCK);
    }

    // ---- service rules ----

    @Test
    void publishRejectsUnapprovedVersion() {
        content.given(1L, item(100L, 1L, version(100L, 1, 1), List.of()));
        runs.given(100L, RunState.WAITING_APPROVAL);

        assertThatThrownBy(() -> service.publish(command(1L, 100L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> assertThat(((PublicationException) error).code())
                        .isEqualTo(PublicationErrorCode.CONTENT_NOT_APPROVED));
        assertThat(publications.created()).isEmpty();
    }

    @Test
    void publishAllowsAnyApprovedVersion() {
        var approvedV1 = version(100L, 1, 1);
        content.given(1L, item(100L, 1L, List.of(
                approvedV1, version(101L, 2, 1)), List.of(
                new ApprovalRecord(1L, 100L, 1L, "ok", NOW))));
        runs.given(100L, RunState.WAITING_APPROVAL);

        Publication created = service.publish(command(1L, 100L));

        assertThat(created.contentVersionId()).isEqualTo(100L);
        assertThat(created.status()).isEqualTo(PublicationStatus.PENDING);
    }

    @Test
    void publishRejectsVersionOfAnotherContent() {
        content.given(1L, item(100L, 1L, version(150L, 1, 1), List.of(
                new ApprovalRecord(2L, 150L, 1L, "ok", NOW))));
        runs.given(100L, RunState.WAITING_APPROVAL);

        assertThatThrownBy(() -> service.publish(command(1L, 200L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> assertThat(((PublicationException) error).code())
                        .isEqualTo(PublicationErrorCode.CONTENT_NOT_LATEST));
    }

    @Test
    void publishRejectsMissingContent() {
        assertThatThrownBy(() -> service.publish(command(99L, 100L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> assertThat(((PublicationException) error).code())
                        .isEqualTo(PublicationErrorCode.CONTENT_NOT_APPROVED));
    }

    @Test
    void publishRejectsInvalidCommand() {
        assertThatThrownBy(() -> service.publish(new PublishContentUseCase.Command(
                0L, 100L, CHANNEL, 1L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> assertThat(((PublicationException) error).code())
                        .isEqualTo(PublicationErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.publish(new PublishContentUseCase.Command(
                1L, 100L, null, 1L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> assertThat(((PublicationException) error).code())
                        .isEqualTo(PublicationErrorCode.VALIDATION_ERROR));
    }

    @Test
    void duplicateRequestReturnsOriginalPublicationAndKey() {
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.WAITING_APPROVAL);

        Publication first = service.publish(command(1L, 100L));
        Publication second = service.publish(command(1L, 100L));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(second.status()).isEqualTo(PublicationStatus.PENDING);
        assertThat(publications.created()).hasSize(1);
    }

    @Test
    void firstRequestCreatesPendingAndMovesRunToPublishing() {
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.WAITING_APPROVAL);

        Publication created = service.publish(command(1L, 100L));

        assertThat(created.status()).isEqualTo(PublicationStatus.PENDING);
        assertThat(created.idempotencyKey()).isNotNull();
        assertThat(runs.updated()).singleElement().satisfies(run ->
                assertThat(run.state()).isEqualTo(RunState.PUBLISHING));
    }

    @Test
    void publishRejectsMissingTitleBeforeCreatingPublication() {
        var legacyTextOnly = version(100L, 3, 1, Map.of("text", "legacy draft"));
        content.given(1L, item(100L, 1L, legacyTextOnly,
                List.of(new ApprovalRecord(1L, 100L, 1L, "ok", NOW))));
        runs.given(100L, RunState.WAITING_APPROVAL);

        assertThatThrownBy(() -> service.publish(command(1L, 100L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> {
                    var publicationError = (PublicationException) error;
                    assertThat(publicationError.code())
                            .isEqualTo(PublicationErrorCode.CONTENT_FORMAT_INVALID);
                    assertThat(publicationError.getMessage()).isEqualTo(
                            "所选 v3 不符合发布格式，缺少 title、body；请选择其他版本尝试发布。");
                });
        assertThat(publications.created()).isEmpty();
        assertThat(runs.findById(100L).orElseThrow().state())
                .isEqualTo(RunState.WAITING_APPROVAL);
    }

    @Test
    void publishRejectsRunOutsidePublishableStates() {
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.RUNNING);

        assertThatThrownBy(() -> service.publish(command(1L, 100L)))
                .isInstanceOf(PublicationException.class)
                .satisfies(error -> assertThat(((PublicationException) error).code())
                        .isEqualTo(PublicationErrorCode.PUBLICATION_CONFLICT));
    }

    @Test
    void completedRunAllowsAdditionalCrossChannelDistribution() {
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.COMPLETED);

        Publication created = service.publish(command(1L, 100L));

        assertThat(created.status()).isEqualTo(PublicationStatus.PENDING);
        assertThat(runs.updated()).isEmpty();
    }

    @Test
    void failedPublicationCanReturnExistingPublishingRunToEditing() {
        var pending = publications.seed(Publication.pending(
                100L, 100L, 1L, 1L, CHANNEL, UUID.randomUUID(), NOW));
        var sending = publications.claimDue(NOW, 1).getFirst();
        publications.markFailed(sending.id(), sending.version(),
                "VALIDATION_ERROR", "content.title 必须是非空字符串");
        runs.given(100L, RunState.PUBLISHING);

        service.returnToEditing(pending.id());

        assertThat(runs.findById(100L).orElseThrow().state())
                .isEqualTo(RunState.WAITING_HUMAN);
    }

    // ---- worker rules ----

    @Test
    void workerClaimsBeforeCallingChannelOutsideAnyTransaction() {
        Publication pending = publications.seed(
                Publication.pending(100L, 100L, 1L, 1L, CHANNEL, UUID.randomUUID(), NOW));
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.PUBLISHING);
        channel.respondWith(new PublishReceipt(UUID.randomUUID(), pending.idempotencyKey(),
                CHANNEL, NOW, false));

        worker.processBatch(NOW);

        assertThat(channel.calls()).singleElement().satisfies(call -> {
            assertThat(call.request().idempotencyKey()).isEqualTo(pending.idempotencyKey());
            assertThat(call.transactionActive()).isFalse();
        });
        assertThat(publications.findById(pending.id()).orElseThrow().status())
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    @Test
    void workerFailsPermanentlyOnChannelRejection() {
        Publication pending = publications.seed(
                Publication.pending(100L, 100L, 1L, 1L, CHANNEL, UUID.randomUUID(), NOW));
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.PUBLISHING);
        channel.throwRejected();

        worker.processBatch(NOW);

        Publication failed = publications.findById(pending.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(PublicationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(failed.failureMessage()).isEqualTo("sandbox rejected");
        assertThat(runs.findById(100L).orElseThrow().state())
                .isEqualTo(RunState.WAITING_HUMAN);
    }

    @Test
    void workerRetriesTransientFailuresAndFailsAfterLimit() {
        Publication pending = publications.seed(
                Publication.pending(100L, 100L, 1L, 1L, CHANNEL, UUID.randomUUID(), NOW));
        content.given(1L, approvedItem(100L, 1L, 100L));
        runs.given(100L, RunState.PUBLISHING);
        channel.throwUnavailable();

        worker.processBatch(NOW);
        var retryWait = publications.findById(pending.id()).orElseThrow();
        assertThat(retryWait.status()).isEqualTo(PublicationStatus.RETRY_WAIT);
        assertThat(retryWait.attemptCount()).isEqualTo(1);
        assertThat(retryWait.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));

        worker.processBatch(NOW.plusSeconds(6));
        worker.processBatch(NOW.plusSeconds(12));

        Publication failed = publications.findById(pending.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(PublicationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(failed.attemptCount()).isEqualTo(3);
        assertThat(runs.findById(100L).orElseThrow().state())
                .isEqualTo(RunState.WAITING_HUMAN);
    }

    @Test
    void workerCompletesRunWhenEveryCurrentApprovedVersionIsPublished() {
        runs.given(100L, RunState.PUBLISHING);
        content.given(1L, approvedItem(100L, 1L, 100L));
        content.given(2L, approvedItem(100L, 2L, 200L));
        var first = publications.seed(Publication.pending(
                100L, 100L, 1L, 1L, CHANNEL, UUID.randomUUID(), NOW));
        var second = publications.seed(Publication.pending(
                100L, 200L, 2L, 1L, CampaignChannel.SOCIAL, UUID.randomUUID(), NOW));
        channel.respondWith(request -> new PublishReceipt(UUID.randomUUID(),
                request.idempotencyKey(), request.channel(), NOW, false));

        worker.processBatch(NOW);
        worker.processBatch(NOW.plusSeconds(1));

        assertThat(publications.findById(first.id()).orElseThrow().status())
                .isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(publications.findById(second.id()).orElseThrow().status())
                .isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(runs.updated()).anySatisfy(run ->
                assertThat(run.state()).isEqualTo(RunState.COMPLETED));
    }

    @Test
    void workerDoesNotCompleteRunWhileAnApprovedVersionIsUnpublished() {
        runs.given(100L, RunState.PUBLISHING);
        content.given(1L, approvedItem(100L, 1L, 100L));
        content.given(2L, approvedItem(100L, 2L, 200L));
        publications.seed(Publication.pending(
                100L, 100L, 1L, 1L, CHANNEL, UUID.randomUUID(), NOW));
        channel.respondWith(request -> new PublishReceipt(UUID.randomUUID(),
                request.idempotencyKey(), request.channel(), NOW, false));

        worker.processBatch(NOW);

        assertThat(runs.updated()).noneMatch(run ->
                run.state() == RunState.COMPLETED);
    }

    private static PublishContentUseCase.Command command(long contentId, long versionId) {
        return new PublishContentUseCase.Command(contentId, versionId, CHANNEL, 1L);
    }

    private static ContentItem approvedItem(long runId, long itemId, long versionId) {
        return item(runId, itemId, version(versionId, 1, 1),
                List.of(new ApprovalRecord(1L, versionId, 1L, "ok", NOW)));
    }

    private static ContentItem item(long runId, long itemId,
                                    ContentVersion version, List<ApprovalRecord> approvals) {
        return item(runId, itemId, List.of(version), approvals);
    }

    private static ContentItem item(long runId, long itemId,
                                    List<ContentVersion> versions,
                                    List<ApprovalRecord> approvals) {
        return new ContentItem(itemId, runId, "create-blog",
                versions.getLast().versionNo(), 0L, NOW, NOW, versions, approvals);
    }

    private static ContentVersion version(long id, int versionNo, long itemId) {
        return version(id, versionNo, itemId, Map.of("title", "T", "body", "B"));
    }

    private static ContentVersion version(long id, int versionNo, long itemId,
                                          Map<String, Object> content) {
        return new ContentVersion(id, itemId, versionNo,
                content, List.of("source-1"),
                ContentOrigin.HUMAN, null, null, null, 1L, NOW);
    }

    private static CampaignRun run(long id, RunState state) {
        return CampaignRun.materialize(id, 10L,
                com.pulseink.domain.execution.ExecutionPolicy.DIRECT, state, null, null,
                List.of(), Map.of(), 0L, null, 0L, null, null, NOW, NOW);
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

    private static final class InMemoryPublicationRepository implements PublicationRepository {

        private final Map<Long, Publication> rows = new LinkedHashMap<>();
        private long nextId = 1;

        List<Publication> created() {
            return List.copyOf(rows.values());
        }

        Publication seed(Publication publication) {
            long id = publication.id() > 0 ? publication.id() : nextId++;
            var stored = new Publication(id, publication.runId(), publication.contentVersionId(),
                    publication.approvalRecordId(), publication.requestedBy(),
                    publication.channel(), publication.idempotencyKey(), publication.status(),
                    publication.attemptCount(), publication.nextAttemptAt(),
                    publication.version(), publication.externalPostId(),
                    publication.receiptJson(), publication.failureCode(),
                    publication.failureMessage(), publication.createdAt(), null, null);
            rows.put(stored.id(), stored);
            return stored;
        }

        @Override
        public Publication createOrGet(Publication pending) {
            for (Publication existing : rows.values()) {
                if (existing.contentVersionId() == pending.contentVersionId()
                        && existing.channel() == pending.channel()) {
                    return existing;
                }
            }
            var stored = new Publication(nextId++, pending.runId(), pending.contentVersionId(),
                    pending.approvalRecordId(), pending.requestedBy(), pending.channel(),
                    pending.idempotencyKey(), pending.status(), pending.attemptCount(),
                    pending.nextAttemptAt(), pending.version(), pending.externalPostId(),
                    pending.receiptJson(), pending.failureCode(), pending.failureMessage(),
                    pending.createdAt(), null, null);
            rows.put(stored.id(), stored);
            return stored;
        }

        @Override
        public Optional<Publication> findById(long publicationId) {
            return Optional.ofNullable(rows.get(publicationId));
        }

        @Override
        public List<Publication> findByRunId(long runId) {
            return rows.values().stream().filter(p -> p.runId() == runId)
                    .sorted(java.util.Comparator.comparingLong(Publication::id)).toList();
        }

        @Override
        public List<Publication> claimDue(Instant now, int batchSize) {
            return rows.values().stream()
                    .filter(p -> p.status() == PublicationStatus.PENDING
                            || p.status() == PublicationStatus.RETRY_WAIT
                            || (p.status() == PublicationStatus.SENDING
                            && p.nextAttemptAt() != null && !p.nextAttemptAt().isAfter(now)))
                    .sorted(java.util.Comparator.comparingLong(Publication::id))
                    .limit(batchSize)
                    .map(p -> with(p, PublicationStatus.SENDING, p.attemptCount() + 1,
                            now.plusSeconds(5), p.version() + 1, p.failureCode(),
                            p.failureMessage(), p.externalPostId(), p.receiptJson(),
                            p.publishedAt()))
                    .peek(p -> rows.put(p.id(), p))
                    .toList();
        }

        @Override
        public boolean claim(long publicationId, long expectedVersion) {
            var existing = rows.get(publicationId);
            if (existing == null || existing.version() != expectedVersion
                    || existing.status() != PublicationStatus.PENDING) {
                return false;
            }
            rows.put(publicationId, with(existing, PublicationStatus.SENDING,
                    existing.attemptCount() + 1, NOW.plusSeconds(5), existing.version() + 1,
                    null, null, null, null, null));
            return true;
        }

        @Override
        public boolean markPublished(long publicationId, long expectedVersion,
                                     PublishReceipt receipt) {
            var existing = rows.get(publicationId);
            if (existing == null || existing.version() != expectedVersion
                    || existing.status() != PublicationStatus.SENDING) {
                return false;
            }
            rows.put(publicationId, with(existing, PublicationStatus.PUBLISHED,
                    existing.attemptCount(), null, existing.version() + 1, null, null,
                    receipt.externalPostId(), "receipt", receipt.publishedAt()));
            return true;
        }

        @Override
        public boolean markRetryWait(long publicationId, long expectedVersion,
                                     Instant nextAttemptAt, String failureCode,
                                     String failureMessage) {
            var existing = rows.get(publicationId);
            if (existing == null || existing.version() != expectedVersion
                    || existing.status() != PublicationStatus.SENDING) {
                return false;
            }
            rows.put(publicationId, with(existing, PublicationStatus.RETRY_WAIT,
                    existing.attemptCount(), nextAttemptAt, existing.version() + 1,
                    failureCode, failureMessage, null, null, null));
            return true;
        }

        @Override
        public boolean markFailed(long publicationId, long expectedVersion,
                                  String failureCode, String failureMessage) {
            var existing = rows.get(publicationId);
            if (existing == null || existing.version() != expectedVersion
                    || existing.status() != PublicationStatus.SENDING) {
                return false;
            }
            rows.put(publicationId, with(existing, PublicationStatus.FAILED,
                    existing.attemptCount(), null, existing.version() + 1,
                    failureCode, failureMessage, null, null, null));
            return true;
        }

        private static Publication with(Publication p, PublicationStatus status, int attempts,
                                        Instant nextAttemptAt, long version, String failureCode,
                                        String failureMessage, UUID externalPostId,
                                        String receiptJson, Instant publishedAt) {
            return new Publication(p.id(), p.runId(), p.contentVersionId(),
                    p.approvalRecordId(), p.requestedBy(), p.channel(), p.idempotencyKey(),
                    status, attempts, nextAttemptAt, version, externalPostId, receiptJson,
                    failureCode, failureMessage, p.createdAt(), p.updatedAt(), publishedAt);
        }
    }

    private static final class RunRepositoryStub implements RunRepository {

        private final Map<Long, CampaignRun> rows = new LinkedHashMap<>();
        private final List<CampaignRun> updates = new ArrayList<>();

        void given(long runId, RunState state) {
            rows.put(runId, run(runId, state));
        }

        List<CampaignRun> updated() {
            return List.copyOf(updates);
        }

        @Override
        public CampaignRun insert(CampaignRun run) {
            rows.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<CampaignRun> findById(long runId) {
            return Optional.ofNullable(rows.get(runId));
        }

        @Override
        public void update(CampaignRun run) {
            rows.put(run.id(), run);
            updates.add(run);
        }

        @Override
        public List<CampaignRun> findByCampaignId(long campaignId) {
            return rows.values().stream()
                    .filter(run -> run.campaignId() == campaignId)
                    .toList();
        }
    }

    private static final class ContentWorkflowRepositoryStub implements ContentWorkflowRepository {

        private final Map<Long, ContentItem> items = new LinkedHashMap<>();

        void given(long contentId, ContentItem item) {
            items.put(contentId, item);
        }

        @Override
        public void captureAgentVersion(long runId, String taskId,
                                        com.pulseink.agent.artifact.AgentArtifact artifact) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void captureReview(long runId, com.pulseink.agent.artifact.AgentArtifact artifact,
                                  com.pulseink.domain.content.ReviewAssessment assessment,
                                  int repairRound) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ContentItem> findByRunId(long runId) {
            return items.values().stream().filter(item -> item.runId() == runId).toList();
        }

        @Override
        public Optional<ContentItem> findById(long contentId) {
            return Optional.ofNullable(items.get(contentId));
        }

        @Override
        public ContentVersion appendHumanVersion(long contentId, int expectedCurrentVersionNo,
                                                 long expectedItemVersion,
                                                 Map<String, Object> content,
                                                 List<String> sourceRefs, long actorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApprovalRecord approve(long contentId, long contentVersionId,
                                      int expectedCurrentVersionNo, long expectedItemVersion,
                                      String comment, long actorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.pulseink.domain.content.ReviewReport> findReviewsByRunId(long runId) {
            return List.of();
        }
    }

    private static final class RecordingChannelPort implements ChannelPort {

        private final List<RecordedCall> calls = new ArrayList<>();
        private PublishReceipt receipt;
        private boolean rejected;
        private boolean unavailable;
        private java.util.function.Function<ChannelPort.PublishRequest, PublishReceipt> responder;

        void respondWith(PublishReceipt receipt) {
            this.receipt = receipt;
        }

        void respondWith(java.util.function.Function<ChannelPort.PublishRequest,
                PublishReceipt> responder) {
            this.responder = responder;
        }

        void throwRejected() {
            this.rejected = true;
        }

        void throwUnavailable() {
            this.unavailable = true;
        }

        List<RecordedCall> calls() {
            return List.copyOf(calls);
        }

        @Override
        public PublishReceipt publish(ChannelPort.PublishRequest request) {
            boolean transactionActive =
                    TransactionSynchronizationManager.isActualTransactionActive();
            calls.add(new RecordedCall(request, transactionActive));
            if (rejected) {
                throw new ChannelRejectedException("IDEMPOTENCY_CONFLICT", "sandbox rejected");
            }
            if (unavailable) {
                throw new ChannelUnavailableException("sandbox unavailable");
            }
            if (responder != null) {
                return responder.apply(request);
            }
            return receipt;
        }

        record RecordedCall(ChannelPort.PublishRequest request, boolean transactionActive) {}
    }
}
