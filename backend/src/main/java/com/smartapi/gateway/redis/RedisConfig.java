package com.smartapi.gateway.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartapi.gateway.cache.NoOpResponseCache;
import com.smartapi.gateway.cache.RedisResponseCache;
import com.smartapi.gateway.cache.ResponseCache;
import com.smartapi.gateway.cache.ResponseCachePolicy;
import com.smartapi.gateway.config.GatewayProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public ResponseCache responseCache(GatewayProperties properties, StringRedisTemplate redis, RedisGuard guard,
                                       ObjectMapper objectMapper, Clock clock) {
        return properties.cache().enabled()
                ? new RedisResponseCache(redis, guard, objectMapper, clock)
                : new NoOpResponseCache();
    }

    /** Lease lasts 3 check intervals: a crashed leader is replaced within ~15 s by default. */
    @Bean
    public LeaderLock healthCheckLeaderLock(StringRedisTemplate redis, RedisGuard guard, GatewayProperties properties) {
        return new LeaderLock(redis, guard, "gateway:leader:health-checker",
                properties.healthCheck().interval().multipliedBy(3));
    }

    @Bean
    public ResponseCachePolicy responseCachePolicy(GatewayProperties properties) {
        return new ResponseCachePolicy(properties.cache().maxTtl(), properties.cache().maxBodyBytes());
    }
}
