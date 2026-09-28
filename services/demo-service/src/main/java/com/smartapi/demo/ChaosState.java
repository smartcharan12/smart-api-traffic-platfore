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

    public enum Mode {
        NONE,
        ERROR,
        SLOW
    }

    public record Settings(Mode mode, long delayMs) {
    }

    private volatile Settings settings = new Settings(Mode.NONE, 0);

    public void set(Mode mode, long delayMs) {
        settings = new Settings(mode, Math.max(0, delayMs));
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
