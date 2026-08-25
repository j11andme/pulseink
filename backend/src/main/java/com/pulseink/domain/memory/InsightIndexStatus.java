package com.pulseink.domain.memory;

/**
 * Derived Elasticsearch index state. The index is always a projection of an APPROVED row; a
 * rejected or pending row stays NOT_INDEXED forever.
 */
public enum InsightIndexStatus {
    NOT_INDEXED,
    INDEX_PENDING,
    INDEXING,
    INDEXED,
    RETRY_WAIT,
    FAILED
}
