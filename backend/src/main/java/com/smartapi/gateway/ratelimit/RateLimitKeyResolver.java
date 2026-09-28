package com.smartapi.gateway.ratelimit;

import com.smartapi.gateway.config.GatewayProperties;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Builds the bucket key from the configured dimensions. Examples:
 * <pre>
 *   [IP]             -> "ip:203.0.113.7"
 *   [USER]           -> "user:alice"         (or "user:anonymous@203.0.113.7" without X-User-Id)
 *   [ENDPOINT]       -> "endpoint:GET /orders"
 *   [USER, ENDPOINT] -> "user:alice|endpoint:GET /orders"
 * </pre>
 * The endpoint uses the service route, not the full path: "/orders/1", "/orders/2", ... would
 * otherwise each get their own bucket, letting a client bypass the limit by varying the id and
 * growing the key map without bound.
 */
@Component
public class RateLimitKeyResolver {

    private final List<RateLimitDimension> dimensions;

    public RateLimitKeyResolver(GatewayProperties properties) {
        this.dimensions = List.copyOf(properties.rateLimit().keyBy());
    }

    public String resolve(String clientIp, String userId, String method, String serviceBasePath) {
        StringJoiner key = new StringJoiner("|");
        for (RateLimitDimension dimension : dimensions) {
            key.add(switch (dimension) {
                case IP -> "ip:" + clientIp;
                case USER -> "user:" + (userId != null ? userId : "anonymous@" + clientIp);
                case ENDPOINT -> "endpoint:" + method + " " + serviceBasePath;
            });
        }
        return key.toString();
    }
}
