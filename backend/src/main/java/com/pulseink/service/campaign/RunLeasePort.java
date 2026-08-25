package com.pulseink.service.campaign;

import java.time.Duration;
import java.util.Optional;

/**
 * Cross-instance run lease port: SET NX PX acquire, token-safe Lua renew/release. A lease only
 * reduces double execution; MySQL optimistic locking remains the final state protection.
 */
public interface RunLeasePort {

    Optional<RunLease> tryAcquire(long runId, String ownerId, Duration ttl);

    boolean renew(RunLease lease, Duration ttl);

    boolean release(RunLease lease);
}
