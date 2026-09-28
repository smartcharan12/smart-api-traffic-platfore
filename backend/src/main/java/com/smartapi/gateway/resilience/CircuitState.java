package com.smartapi.gateway.resilience;

/**
 * <pre>
 *            failure rate >= threshold               openDuration elapsed
 *   CLOSED ------------------------------> OPEN ------------------------> HALF_OPEN
 *     ^                                     ^                                 |
 *     |        all trial calls succeed      |        any trial call fails      |
 *     +-------------------------------------+---------------------------------+
 * </pre>
 * The numeric level is exported to Prometheus (0 = closed, 1 = half open, 2 = open).
 */
public enum CircuitState {
    CLOSED(0),
    HALF_OPEN(1),
    OPEN(2);

    private final int level;

    CircuitState(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}
