package com.pulseink.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunEventServiceTest {

    private final FakeRunJournal journal = new FakeRunJournal();
    private final RunEventService service = new RunEventService(journal);

    @Test
    void appendAndPublishDeliversToSubscriberAfterPersist() {
        var received = new ArrayList<RunEvent>();
        service.subscribe(1L, received::add);

        service.appendAndPublish(1L, RunEventType.RUN_STATE_CHANGED,
                Map.of("fromState", "CREATED", "toState", "RUNNING"));

        assertThat(journal.events).hasSize(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).sequence()).isEqualTo(1L);
    }

    @Test
    void checkpointAndArtifactEventArePublishedAsOneJournalOperation() {
        var received = new ArrayList<RunEvent>();
        service.subscribe(1L, received::add);
        var checkpoint = RunCheckpoint.of(
                1L, "ARTIFACT", List.of(), BudgetSnapshot.ZERO,
                1, 0L, Instant.now());

        var event = service.saveCheckpointAndPublish(
                checkpoint, RunEventType.ARTIFACT_CREATED,
                Map.of("artifactId", "artifact-1"));

        assertThat(journal.checkpoints).hasSize(1);
        assertThat(journal.checkpointEventTypes)
                .containsExactly(RunEventType.ARTIFACT_CREATED);
        assertThat(received).containsExactly(event);
    }

    @Test
    void replayThenSubscribeReplaysOnlyEventsAfterLastId() {
        service.appendAndPublish(1L, RunEventType.EXECUTION_MODE_SELECTED, Map.of());
        service.appendAndPublish(1L, RunEventType.RUN_STATE_CHANGED, Map.of());
        service.appendAndPublish(1L, RunEventType.DECISION_RECORDED, Map.of());

        var received = new ArrayList<RunEvent>();
        var liveSubscriber = (RunEventService.Subscriber) received::add;
        var replayed = service.replayThenSubscribe(1L, 1L, liveSubscriber);

        assertThat(replayed).extracting(RunEvent::sequence).containsExactly(2L, 3L);
        assertThat(received).extracting(RunEvent::sequence).containsExactly(2L, 3L);

        service.appendAndPublish(1L, RunEventType.DECISION_RECORDED, Map.of());
        assertThat(received).extracting(RunEvent::sequence).containsExactly(2L, 3L, 4L);
    }

    @Test
    void concurrentAppendsKeepMonotonicSequenceWithoutDuplicates() throws Exception {
        int threads = 8;
        int perThread = 25;
        var barrier = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        var failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    barrier.countDown();
                    barrier.await();
                    for (int j = 0; j < perThread; j++) {
                        service.appendAndPublish(1L, RunEventType.DECISION_RECORDED, Map.of());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(0);
        assertThat(journal.events).hasSize(threads * perThread);
        var sequences = journal.events.stream().map(RunEvent::sequence).toList();
        assertThat(sequences).containsExactly(
                sequences.stream().sorted().toList().toArray(new Long[0]));
        assertThat(sequences).doesNotHaveDuplicates();
    }

    @Test
    void unsubscribeStopsDelivery() {
        var received = new ArrayList<RunEvent>();
        var subscriber = (RunEventService.Subscriber) received::add;
        service.subscribe(1L, subscriber);
        service.unsubscribe(1L, subscriber);
        service.appendAndPublish(1L, RunEventType.RUN_STATE_CHANGED, Map.of());

        assertThat(received).isEmpty();
        assertThat(service.subscriberCount(1L)).isZero();
    }

    private static final class FakeRunJournal implements RunJournal {
        private final List<RunEvent> events = new ArrayList<>();
        private final List<RunCheckpoint> checkpoints = new ArrayList<>();
        private final List<RunEventType> checkpointEventTypes = new ArrayList<>();
        private long sequence;

        @Override
        public synchronized RunEvent appendEvent(long runId, RunEventType type,
                                                 Map<String, Object> payload) {
            var event = new RunEvent(runId, ++sequence, type, payload, java.time.Instant.now());
            events.add(event);
            return event;
        }

        @Override
        public RunEvent saveCheckpointAndAppendEvent(
                RunCheckpoint checkpoint,
                RunEventType type, Map<String, Object> payload) {
            checkpoints.add(checkpoint);
            checkpointEventTypes.add(type);
            return appendEvent(checkpoint.runId(), type, payload);
        }

        @Override
        public Optional<RunCheckpoint> latestCheckpoint(
                long runId) {
            return Optional.empty();
        }

        @Override
        public List<RunEvent> findEventsAfter(long runId, long lastSequence) {
            return events.stream()
                    .filter(event -> event.runId() == runId
                            && event.sequence() > lastSequence)
                    .toList();
        }
    }
}
