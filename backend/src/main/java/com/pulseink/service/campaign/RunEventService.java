package com.pulseink.service.campaign;

import com.pulseink.agent.checkpoint.RunCheckpoint;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists run events in short transactions and publishes them to in-memory subscribers only
 * after commit. A per-runId lock coordinates append/publish so live subscribers never observe
 * a gap while {@link #replayThenSubscribe} is running.
 */
public class RunEventService {

    private final RunJournal journal;
    private final ConcurrentHashMap<Long, Object> runLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Subscriber>> subscribers =
            new ConcurrentHashMap<>();

    public RunEventService(RunJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
    }

    public RunEvent appendAndPublish(long runId, RunEventType type,
                                     Map<String, Object> payload) {
        RunEvent event;
        synchronized (lock(runId)) {
            event = journal.appendEvent(runId, type, payload);
            publish(runId, event);
        }
        return event;
    }

    /**
     * Atomically persists a checkpoint with its corresponding event, then publishes the
     * committed event to live subscribers while holding the same per-run ordering lock.
     */
    public RunEvent saveCheckpointAndPublish(
            RunCheckpoint checkpoint,
            RunEventType type,
            Map<String, Object> payload) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        RunEvent event;
        synchronized (lock(checkpoint.runId())) {
            event = journal.saveCheckpointAndAppendEvent(checkpoint, type, payload);
            publish(checkpoint.runId(), event);
        }
        return event;
    }

    public void subscribe(long runId, Subscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        synchronized (lock(runId)) {
            subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>())
                    .add(subscriber);
        }
    }

    public void unsubscribe(long runId, Subscriber subscriber) {
        synchronized (lock(runId)) {
            var list = subscribers.get(runId);
            if (list != null) {
                list.remove(subscriber);
            }
        }
    }

    public int subscriberCount(long runId) {
        var list = subscribers.get(runId);
        return list == null ? 0 : list.size();
    }

    public RunJournal journal() {
        return journal;
    }

    /**
     * Replays persisted events with sequence greater than {@code lastEventId} inside the same
     * per-runId lock used by {@link #appendAndPublish}, then registers the subscriber. Replay
     * delivery happens under the lock so replay/live ordering is never interleaved; the returned
     * list is the replayed events for callers that want to inspect them.
     */
    public List<RunEvent> replayThenSubscribe(long runId, long lastEventId,
                                              Subscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        synchronized (lock(runId)) {
            var replayed = journal.findEventsAfter(runId, lastEventId);
            subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>())
                    .add(subscriber);
            for (var event : replayed) {
                subscriber.onEvent(event);
            }
            return List.copyOf(replayed);
        }
    }

    public void unsubscribeAll(long runId) {
        synchronized (lock(runId)) {
            subscribers.remove(runId);
        }
    }

    private void publish(long runId, RunEvent event) {
        var list = subscribers.get(runId);
        if (list == null) {
            return;
        }
        for (var subscriber : list) {
            subscriber.onEvent(event);
        }
    }

    private Object lock(long runId) {
        return runLocks.computeIfAbsent(runId, ignored -> new Object());
    }

    @FunctionalInterface
    public interface Subscriber {
        void onEvent(RunEvent event);
    }
}
