package com.pulseink.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.service.campaign.RunJournal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultMemoryPortTest {

    @Test
    void cacheWithSameEventSequenceButOlderCheckpointIsRebuiltFromMysql() {
        var oldMemory = new RunWorkingMemory(
                2L, "ARTIFACT", 1, 0, 0L,
                Instant.parse("2026-08-14T08:00:00Z"), List.of(), BudgetSnapshot.ZERO);
        var latest = RunCheckpoint.of(
                2L, "ARTIFACT", List.of(), BudgetSnapshot.ZERO, 1, 0L,
                Instant.parse("2026-08-14T08:00:01Z"));
        var journal = mock(RunJournal.class);
        when(journal.latestCheckpoint(2L)).thenReturn(Optional.of(latest));
        var cached = new AtomicReference<RunWorkingMemory>(oldMemory);
        RunWorkingMemoryCache cache = new RunWorkingMemoryCache() {
            @Override
            public Optional<RunWorkingMemory> load(long runId) {
                return Optional.ofNullable(cached.get());
            }

            @Override
            public void put(long runId, RunWorkingMemory memory) {
                cached.set(memory);
            }

            @Override
            public void invalidate(long runId) {
                cached.set(null);
            }
        };
        var port = new DefaultMemoryPort(
                journal, mock(MemorySourceRepository.class),
                mock(InsightSearchStore.class), cache, 3);

        var result = port.loadRunWorkingMemory(2L);

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.memory().createdAt()).isEqualTo(latest.createdAt());
        assertThat(result.memory().lastCompletedRound()).isEqualTo(1);
    }
}
