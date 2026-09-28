package com.smartapi.gateway.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Simple leader election with a Redis lock: {@code SET key <myId> NX PX ttl}. The holder renews the
 * TTL every cycle; if it dies, the key expires and another gateway takes over within one TTL.
 *
 * <p>Acquire-or-renew is one Lua script, so "is it mine? then extend it" can't race with another
 * gateway acquiring it in between. If Redis is unavailable we return {@code true}: every gateway
 * then does the work. For health checks that only means duplicated probes, which is safe.
 * (For work that must never run twice, a lease like this is not enough; you'd need fencing tokens.)
 */
public class LeaderLock {

    private static final DefaultRedisScript<Long> ACQUIRE_OR_RENEW = new DefaultRedisScript<>("""
            local holder = redis.call('GET', KEYS[1])
            if holder == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if holder == false then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final String key;
    private final Duration ttl;
    private final String id = UUID.randomUUID().toString();

    public LeaderLock(StringRedisTemplate redis, RedisGuard guard, String key, Duration ttl) {
        this.redis = redis;
        this.guard = guard;
        this.key = key;
        this.ttl = ttl;
    }

    /** @return true if this process is (still) the leader, or if Redis can't be asked */
    public boolean acquireOrRenew() {
        return guard.call(() -> redis.execute(ACQUIRE_OR_RENEW, List.of(key), id, String.valueOf(ttl.toMillis())))
                .map(result -> result == 1L)
                .orElse(true);
    }

    public String id() {
        return id;
    }
}
