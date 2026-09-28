package com.smartapi.gateway.cache;

import com.smartapi.gateway.routing.GatewayRequest;
import com.smartapi.gateway.routing.GatewayResponse;
import com.smartapi.gateway.routing.Route;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/**
 * Decides what a SHARED cache may store. The gateway serves one cached copy to every client, so it
 * must never store something personal or something the backend didn't explicitly allow:
 * <ul>
 *   <li>request: GET only, and no {@code Authorization} or {@code Cookie} header (personalised);</li>
 *   <li>response: 200, with {@code Cache-Control: public/max-age=N} or {@code s-maxage=N} (N > 0), and no
 *       {@code private}, {@code no-store} or {@code no-cache}, no {@code Set-Cookie}, and no {@code Vary}
 *       other than {@code Accept-Encoding} (we don't key on request headers);</li>
 *   <li>body no larger than {@code maxBodyBytes}; TTL = min(max-age, maxTtl).</li>
 * </ul>
 * The backend owns freshness (HTTP caching semantics, RFC 9111); the gateway never guesses a TTL.
 */
public final class ResponseCachePolicy {

    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*(s-maxage|max-age)\\s*=\\s*(\\d+)");

    private final Duration maxTtl;
    private final int maxBodyBytes;

    public ResponseCachePolicy(Duration maxTtl, int maxBodyBytes) {
        this.maxTtl = maxTtl;
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Cache key for the request, or empty if the request must not use the shared cache. */
    public Optional<String> keyFor(GatewayRequest request, Route route) {
        if (!"GET".equals(request.method())
                || request.headers().containsKey(HttpHeaders.AUTHORIZATION)
                || request.headers().containsKey(HttpHeaders.COOKIE)) {
            return Optional.empty();
        }
        String query = request.query() == null ? "" : "?" + request.query();
        return Optional.of("rc:" + route.basePath() + route.downstreamPath() + query);
    }

    /** How long the response may be cached, or empty if it must not be. */
    public Optional<Duration> ttlFor(GatewayResponse response) {
        if (response.status() != 200 || response.body().length > maxBodyBytes
                || response.headers().containsKey(HttpHeaders.SET_COOKIE)) {
            return Optional.empty();
        }
        List<String> vary = response.headers().getOrEmpty(HttpHeaders.VARY);
        if (vary.stream().anyMatch(v -> !v.trim().equalsIgnoreCase("Accept-Encoding"))) {
            return Optional.empty();
        }
        String cacheControl = String.join(",", response.headers().getOrEmpty(HttpHeaders.CACHE_CONTROL))
                .toLowerCase(Locale.ROOT);
        if (cacheControl.contains("no-store") || cacheControl.contains("no-cache") || cacheControl.contains("private")) {
            return Optional.empty();
        }
        long seconds = -1;
        Matcher m = MAX_AGE.matcher(cacheControl);
        while (m.find()) {
            long value = Long.parseLong(m.group(2));
            // s-maxage is specifically for shared caches (like this gateway) and takes precedence.
            if (m.group(1).equals("s-maxage") || seconds < 0) {
                seconds = value;
            }
        }
        if (seconds <= 0) {
            return Optional.empty();
        }
        Duration ttl = Duration.ofSeconds(seconds);
        return Optional.of(ttl.compareTo(maxTtl) > 0 ? maxTtl : ttl);
    }
}
