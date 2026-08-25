package com.pulseink.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FakeModelAdapterTest {

    private static final ModelRequest REQUEST =
            new ModelRequest("req-1", "system", "user", null, null);

    @Test
    void emitsTheDeterministicFourEventSequence() throws InterruptedException {
        var adapter = new FakeModelAdapter(Duration.ZERO);
        var events = new CopyOnWriteArrayList<ModelStreamEvent>();
        var completed = new CountDownLatch(1);

        adapter.stream(REQUEST, event -> {
            events.add(event);
            if (event instanceof Completed) {
                completed.countDown();
            }
        });

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ContentDelta", "ContentDelta", "Completed");
        assertThat(events.stream()
                        .filter(ContentDelta.class::isInstance)
                        .map(ContentDelta.class::cast)
                        .map(ContentDelta::content))
                .containsExactly("Pulse", "Ink");
    }

    @Test
    void cancellationStopsTheRemainingEvents() throws InterruptedException {
        var adapter = new FakeModelAdapter(Duration.ofMillis(80));
        var events = new CopyOnWriteArrayList<ModelStreamEvent>();
        var firstDelta = new CountDownLatch(1);
        var completed = new CountDownLatch(1);

        var handle = adapter.stream(REQUEST, event -> {
            events.add(event);
            if (event instanceof ContentDelta) {
                firstDelta.countDown();
            }
            if (event instanceof Completed) {
                completed.countDown();
            }
        });

        assertThat(firstDelta.await(2, TimeUnit.SECONDS)).isTrue();
        handle.cancel();

        assertThat(completed.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(events).noneMatch(Completed.class::isInstance);
        assertThat(List.copyOf(events))
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ContentDelta");
    }
}
