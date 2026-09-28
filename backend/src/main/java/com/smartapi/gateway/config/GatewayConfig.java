package com.smartapi.gateway.config;

import com.smartapi.gateway.ratelimit.FallbackRateLimiter;
import com.smartapi.gateway.ratelimit.FixedWindowRateLimiter;
import com.smartapi.gateway.ratelimit.NoOpRateLimiter;
import com.smartapi.gateway.ratelimit.RateLimitStore;
import com.smartapi.gateway.ratelimit.RateLimiter;
import com.smartapi.gateway.ratelimit.RedisRateLimiter;
import com.smartapi.gateway.ratelimit.SlidingWindowRateLimiter;
import com.smartapi.gateway.ratelimit.TokenBucketRateLimiter;
import com.smartapi.gateway.redis.RedisGuard;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/** Wires the gateway's infrastructure: the outbound HTTP client and the configured rate limiter. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    /**
     * One shared client for all forwarding. The JDK HttpClient keeps a pool of keep-alive connections
     * per host, so repeated requests to the same instance reuse a TCP connection instead of paying
     * for a new handshake each time.
     */
    @Bean
    public HttpClient gatewayHttpClient(GatewayProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)        // plain-HTTP backends; skip the h2c upgrade attempt
                .connectTimeout(properties.proxy().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)  // a proxy passes 3xx to the client untouched
                .build();
    }

    @Bean
    public RestClient gatewayRestClient(HttpClient gatewayHttpClient, GatewayProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(gatewayHttpClient);
        requestFactory.setReadTimeout(properties.proxy().responseTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * The gateway depends on the RateLimiter interface; this factory method is the only place that
     * knows which implementation is used. Switching algorithm is a config change, not a code change.
     */
    @Bean
    public RateLimiter rateLimiter(GatewayProperties properties, Clock clock, StringRedisTemplate redis,
                                   RedisGuard redisGuard) {
        GatewayProperties.RateLimit config = properties.rateLimit();
        if (!config.enabled()) {
            return new NoOpRateLimiter();
        }
        RateLimiter local = switch (config.algorithm()) {
            case FIXED_WINDOW -> new FixedWindowRateLimiter(config.limit(), config.window(), clock);
            case SLIDING_WINDOW -> new SlidingWindowRateLimiter(config.limit(), config.window(), clock);
            case TOKEN_BUCKET -> TokenBucketRateLimiter.forRate(config.limit(), config.window(), clock);
        };
        if (config.store() == RateLimitStore.LOCAL) {
            return local;
        }
        RateLimiter shared = new RedisRateLimiter(redis, config.algorithm(), config.limit(), config.window(), clock);
        return new FallbackRateLimiter(shared, local, redisGuard);
    }
}
