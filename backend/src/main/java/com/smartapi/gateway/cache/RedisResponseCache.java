package com.smartapi.gateway.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartapi.gateway.redis.RedisGuard;
import com.smartapi.gateway.routing.GatewayResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;

/**
 * Stores responses in Redis as JSON (status, headers, base64 body, storedAt) with a TTL, so every
 * gateway instance shares one cache and Redis expires entries itself. Redis problems are treated
 * as a cache miss (via RedisGuard): the request just goes to the backend.
 */
public class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);

    /** Only end-to-end representation headers are cached; per-connection/per-request ones are not. */
    private static final List<String> STORED_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CACHE_CONTROL,
            HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.CONTENT_LANGUAGE, HttpHeaders.EXPIRES);

    record Stored(int status, Map<String, List<String>> headers, String body, long storedAt) {
    }

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final ObjectMapper json;
    private final Clock clock;

    public RedisResponseCache(StringRedisTemplate redis, RedisGuard guard, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.guard = guard;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        return guard.call(() -> redis.opsForValue().get(key)).flatMap(this::decode);
    }

    @Override
    public void put(String key, GatewayResponse response, Duration ttl) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : STORED_HEADERS) {
            List<String> values = response.headers().get(name);
            if (values != null) {
                headers.put(name, values);
            }
        }
        try {
            String value = json.writeValueAsString(new Stored(response.status(), headers,
                    Base64.getEncoder().encodeToString(response.body()), clock.millis()));
            guard.run(() -> redis.opsForValue().set(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise response for cache key {}", key, e);
        }
    }

    private Optional<CachedResponse> decode(String value) {
        try {
            Stored stored = json.readValue(value, Stored.class);
            HttpHeaders headers = new HttpHeaders();
            stored.headers().forEach(headers::addAll);
            return Optional.of(new CachedResponse(
                    new GatewayResponse(stored.status(), headers, Base64.getDecoder().decode(stored.body())),
                    stored.storedAt()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Discarding unreadable cache entry: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
