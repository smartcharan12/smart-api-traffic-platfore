package com.smartapi.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.routing.GatewayRequest;
import com.smartapi.gateway.routing.GatewayResponse;
import com.smartapi.gateway.routing.Route;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ResponseCachePolicyTest {

    private final ResponseCachePolicy policy = new ResponseCachePolicy(Duration.ofMinutes(5), 1024);
    private final Route route = new Route("/restaurants", "/restaurants/1");

    private static GatewayRequest request(String method, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.add(headerPairs[i], headerPairs[i + 1]);
        }
        return new GatewayRequest(method, "/gateway/restaurants/restaurants/1", "lang=en", headers, null, "127.0.0.1", null);
    }

    private static GatewayResponse response(int status, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.add(headerPairs[i], headerPairs[i + 1]);
        }
        return new GatewayResponse(status, headers, "{}".getBytes());
    }

    @Test
    void onlyAnonymousGetRequestsUseTheSharedCache() {
        assertThat(policy.keyFor(request("GET"), route)).contains("rc:/restaurants/restaurants/1?lang=en");
        assertThat(policy.keyFor(request("POST"), route)).isEmpty();
        assertThat(policy.keyFor(request("GET", "Authorization", "Bearer x"), route)).isEmpty();
        assertThat(policy.keyFor(request("GET", "Cookie", "session=1"), route)).isEmpty();
    }

    @Test
    void backendDecidesTheTtl() {
        assertThat(policy.ttlFor(response(200, "Cache-Control", "public, max-age=30"))).contains(Duration.ofSeconds(30));
        assertThat(policy.ttlFor(response(200, "Cache-Control", "max-age=30, s-maxage=10"))).contains(Duration.ofSeconds(10));
        assertThat(policy.ttlFor(response(200, "Cache-Control", "max-age=86400"))).contains(Duration.ofMinutes(5)); // capped
    }

    @Test
    void anythingNotExplicitlyCacheableIsNotStored() {
        assertThat(policy.ttlFor(response(200))).isEmpty();                                            // no header
        assertThat(policy.ttlFor(response(200, "Cache-Control", "no-store"))).isEmpty();
        assertThat(policy.ttlFor(response(200, "Cache-Control", "private, max-age=60"))).isEmpty();
        assertThat(policy.ttlFor(response(200, "Cache-Control", "max-age=0"))).isEmpty();
        assertThat(policy.ttlFor(response(500, "Cache-Control", "max-age=60"))).isEmpty();
        assertThat(policy.ttlFor(response(200, "Cache-Control", "max-age=60", "Set-Cookie", "a=b"))).isEmpty();
        assertThat(policy.ttlFor(response(200, "Cache-Control", "max-age=60", "Vary", "Authorization"))).isEmpty();
        assertThat(policy.ttlFor(response(200, "Cache-Control", "max-age=60", "Vary", "Accept-Encoding"))).isPresent();
        GatewayResponse big = new GatewayResponse(200, response(200, "Cache-Control", "max-age=60").headers(), new byte[2048]);
        assertThat(policy.ttlFor(big)).isEmpty();
    }
}
