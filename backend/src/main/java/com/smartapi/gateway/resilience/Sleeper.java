package com.smartapi.gateway.resilience;

import java.time.Duration;

/** Waiting between retries, behind an interface so tests record the delays instead of sleeping. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
