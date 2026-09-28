package com.smartapi.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * The same three algorithms as the in-memory limiters, with state in Redis so that every gateway
 * instance shares one budget per key. Each check is ONE Lua script call: Redis executes scripts
 * atomically, so "read, compare with limit, write" can't interleave between gateways (the
 * distributed version of the {@code count++} race).
 *
 * <p>"now" is passed in from the gateway's Clock, which keeps tests deterministic. Trade-off: gateways
 * must have roughly synchronised clocks (NTP keeps them within milliseconds, far below any window).
 * The alternative, {@code redis.call('TIME')}, avoids skew but can't be controlled in tests.
 *
 * <p>Keys are {@code rl:<algorithm>:<bucket key>}, each with a TTL, so idle keys clean themselves up.
 */
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;
    private final RateLimitAlgorithm algorithm;
    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final DefaultRedisScript<List<Long>> script;
    private final String prefix;

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitAlgorithm algorithm, int limit, Duration window,
                            Clock clock) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit must be >= 1 and window must be positive");
        }
        this.redis = redis;
        this.algorithm = algorithm;
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
        String name = algorithm.name().toLowerCase(Locale.ROOT);
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        this.script.setResultType(listOfLong());
        this.prefix = "rl:" + name + ":";
    }

    /**
     * Java generics are erased at runtime, so there is no {@code List<Long>.class}. The cast is safe:
     * Redis returns a Lua table of integers as a List of Longs.
     */
    @SuppressWarnings("unchecked")
    private static Class<List<Long>> listOfLong() {
        return (Class<List<Long>>) (Class<?>) List.class;
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        long now = clock.millis();
        List<String> keys = List.of(prefix + key);
        List<Long> result = switch (algorithm) {
            case FIXED_WINDOW -> redis.execute(script, keys, str(limit), str(windowMillis), str(now));
            case SLIDING_WINDOW -> redis.execute(script, keys, str(limit), str(windowMillis), str(now),
                    now + "-" + UUID.randomUUID());
            case TOKEN_BUCKET -> redis.execute(script, keys, str(limit), Double.toString(limit / (double) windowMillis),
                    str(now));
        };
        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Unexpected rate-limit script result: " + result);
        }
        return result.get(0) == 1L
                ? RateLimitDecision.allow(limit, result.get(1).intValue())
                : RateLimitDecision.reject(limit, Duration.ofMillis(Math.max(1, result.get(2))));
    }

    private static String str(long value) {
        return Long.toString(value);
    }
}
