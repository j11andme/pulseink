package com.pulseink.service.campaign;

import com.pulseink.agent.api.ExecutionOwnershipGuard;
import com.pulseink.agent.api.ExecutionOwnershipLostException;
import com.pulseink.config.properties.RunLeaseProperties;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lease lifecycle manager. Fail-closed semantics: Redis unavailable or already leased means
 * this owner must skip the run without touching any state. A background renewer keeps the
 * lease alive; the first failed renewal permanently flips the ownership guard, after which no
 * new model/tool/checkpoint side effect may start and the terminal release becomes a no-op.
 */
public final class RunLeaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunLeaseManager.class);

    private final RunLeasePort port;
    private final RunLeaseProperties properties;
    private final String ownerId;
    private final ScheduledExecutorService renewer;

    public RunLeaseManager(RunLeasePort port, RunLeaseProperties properties, String ownerId) {
        this(port, properties, ownerId,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "pulseink-run-lease-renewer");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    public RunLeaseManager(RunLeasePort port, RunLeaseProperties properties, String ownerId,
                           ScheduledExecutorService renewer) {
        this.port = Objects.requireNonNull(port);
        this.properties = Objects.requireNonNull(properties);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.renewer = Objects.requireNonNull(renewer);
    }

    public LeaseHandle tryAcquire(long runId) {
        if (!properties.enabled()) {
            return LeaseHandle.noop();
        }
        RunLease lease;
        try {
            lease = port.tryAcquire(runId, ownerId, properties.ttl()).orElse(null);
        } catch (RuntimeException redisDown) {
            log.warn("RUN_LEASE_UNAVAILABLE runId={}", runId);
            return LeaseHandle.skipped();
        }
        if (lease == null) {
            log.info("RUN_LEASE_SKIPPED runId={}", runId);
            return LeaseHandle.skipped();
        }
        var owned = new AtomicBoolean(true);
        ScheduledFuture<?> scheduled = renewer.scheduleAtFixedRate(() -> {
            if (!owned.get()) {
                return;
            }
            boolean renewed = false;
            try {
                renewed = port.renew(lease, properties.ttl());
            } catch (RuntimeException ignored) {
                // treated as renewal failure below
            }
            if (!renewed) {
                owned.set(false);
                log.warn("RUN_LEASE_RENEWAL_FAILED runId={}", runId);
            }
        }, properties.renewInterval().toMillis(), properties.renewInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        return new ActiveHandle(lease, owned, scheduled);
    }

    @Override
    public void close() {
        renewer.shutdownNow();
    }

    public interface LeaseHandle extends AutoCloseable {

        ExecutionOwnershipGuard guard();

        boolean owned();

        @Override
        void close();

        static LeaseHandle noop() {
            return new LeaseHandle() {
                @Override
                public ExecutionOwnershipGuard guard() {
                    return ExecutionOwnershipGuard.noop();
                }

                @Override
                public boolean owned() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }

        static LeaseHandle skipped() {
            return new LeaseHandle() {
                @Override
                public ExecutionOwnershipGuard guard() {
                    return () -> {
                        throw new ExecutionOwnershipLostException(-1);
                    };
                }

                @Override
                public boolean owned() {
                    return false;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private final class ActiveHandle implements LeaseHandle {

        private final RunLease lease;
        private final AtomicBoolean owned;
        private final ScheduledFuture<?> scheduled;

        private ActiveHandle(RunLease lease, AtomicBoolean owned, ScheduledFuture<?> scheduled) {
            this.lease = lease;
            this.owned = owned;
            this.scheduled = scheduled;
        }

        @Override
        public ExecutionOwnershipGuard guard() {
            return () -> {
                if (!owned.get()) {
                    throw new ExecutionOwnershipLostException(lease.runId());
                }
            };
        }

        @Override
        public boolean owned() {
            return owned.get();
        }

        @Override
        public void close() {
            owned.set(false);
            scheduled.cancel(false);
            try {
                port.release(lease);
            } catch (RuntimeException ignored) {
                // TTL releases the lease when Redis is gone.
            }
        }
    }
}
