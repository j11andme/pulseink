package com.pulseink.agent.api;

/**
 * Framework-free execution ownership boundary. Engines check it immediately before every
 * model, tool and persistence side effect; it expresses nothing about Redis or Spring and is
 * only about whether the current owner may still proceed.
 */
@FunctionalInterface
public interface ExecutionOwnershipGuard {

    void assertCanProceed();

    static ExecutionOwnershipGuard noop() {
        return () -> {};
    }
}
