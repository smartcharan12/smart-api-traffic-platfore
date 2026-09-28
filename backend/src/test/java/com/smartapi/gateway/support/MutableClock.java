package com.smartapi.gateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A Clock that only moves when the test says so. Rate-limiter tests "wait" 59 seconds in
 * nanoseconds, and the result is the same on every run and on any machine: no Thread.sleep, no
 * flakiness when CI is slow. {@code volatile} because concurrency tests read it from many threads.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    /** 12:00:00.000 UTC: aligned to a minute boundary, so fixed windows start exactly here. */
    public static MutableClock atNoon() {
        return new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
