package com.smartapi.demo;

import org.springframework.stereotype.Component;

/**
 * Current failure mode. Written by one request thread (ChaosController) and read by every other
 * request thread (ChaosInterceptor), so the fields are volatile: a write is visible to all threads
 * immediately. Mode and delay are set together through one immutable object so a reader can never
 * see a new mode with an old delay.
 */
@Component
public class ChaosState {

    /** ERROR = HTTP 500, UNAVAILABLE = HTTP 503, SLOW = wait delayMs, then answer normally. */
    public enum Mode {
        NONE,
        ERROR,
        UNAVAILABLE,
        SLOW
    }

    /**
     * @param includeHealth also apply the mode to /health. Off: the instance is "alive but broken"
     *                      (only the gateway's circuit breaker notices). On: health probes fail or are
     *                      slow too, so the health checker moves it to DEGRADED / DOWN.
     */
    public record Settings(Mode mode, long delayMs, boolean includeHealth) {
    }

    private volatile Settings settings = new Settings(Mode.NONE, 0, false);

    public void set(Mode mode, long delayMs) {
        set(mode, delayMs, false);
    }

    public void set(Mode mode, long delayMs, boolean includeHealth) {
        settings = new Settings(mode, Math.max(0, Math.min(delayMs, 60_000)), includeHealth);
    }

    public Mode mode() {
        return settings.mode();
    }

    public long delayMs() {
        return settings.delayMs();
    }

    public Settings snapshot() {
        return settings;
    }
}
