package com.pulseink.service.memory;

import java.util.Optional;

/**
 * Rebuildable hot cache for run working memory. Redis data loss must never lose business
 * information: a miss simply rebuilds from the MySQL checkpoint.
 */
public interface RunWorkingMemoryCache {

    Optional<RunWorkingMemory> load(long runId);

    void put(long runId, RunWorkingMemory memory);

    void invalidate(long runId);
}
