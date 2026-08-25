package com.pulseink.sandbox.domain;

/**
 * Channel vocabulary accepted by the simulated channel. Mirrors the backend campaign channel
 * names so the two applications stay decoupled yet contract-compatible.
 */
public enum Channel {
    BLOG,
    SOCIAL,
    SHORT_VIDEO;

    public static Channel fromName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }
}
