package com.smartapi.gateway.redis;

import com.smartapi.gateway.resilience.CircuitBreaker;
import com.smartapi.gateway.resilience.CircuitBreakerConfig;
import com.smartapi.gateway.resilience.CircuitState;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every Redis call goes through here. Redis is an optimisation for this gateway, never a hard
 * dependency: if a call fails the caller gets {@code Optional.empty()} and uses its fallback.
 *
 * <p>Reuses our own {@link CircuitBreaker}: after repeated failures Redis is skipped entirely for a few
 * seconds, so requests don't each pay a connection timeout while Redis is down. The breaker's
 * half-open trial call detects when Redis is back.
 */
@Component
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final CircuitBreaker breaker;
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public RedisGuard(Clock clock) {
        this.breaker = new CircuitBreaker("redis", new CircuitBreakerConfig(10, 3, 50, Duration.ofSeconds(5), 1),
                clock, t -> {
                    if (t.to() == CircuitState.OPEN) {
                        log.warn("Redis unavailable ({}); using local fallbacks for {} s", t.reason(), 5);
                    } else if (t.to() == CircuitState.CLOSED) {
                        log.info("Redis available again");
                    }
                });
    }

    /** @return the action's result, or empty if Redis is unavailable or the action returned null */
    public <T> Optional<T> call(Supplier<T> action) {
        if (!breaker.tryAcquirePermission()) {
            skipped.incrementAndGet();
            return Optional.empty();
        }
        try {
            T result = action.get();
            breaker.onSuccess();
            return Optional.ofNullable(result);
        } catch (RuntimeException e) {
            breaker.onFailure();
            failures.incrementAndGet();
            log.debug("Redis call failed: {}", e.toString());
            return Optional.empty();
        }
    }

    public boolean run(Runnable action) {
        return call(() -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    public CircuitState state() {
        return breaker.state();
    }

    public long failures() {
        return failures.get();
    }

    public long skipped() {
        return skipped.get();
    }
}
