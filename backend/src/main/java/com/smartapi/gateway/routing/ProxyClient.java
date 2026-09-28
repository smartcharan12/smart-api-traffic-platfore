package com.smartapi.gateway.routing;

import com.smartapi.gateway.exception.BadGatewayException;
import com.smartapi.gateway.exception.InvalidGatewayRequestException;
import com.smartapi.gateway.exception.UpstreamTimeoutException;
import com.smartapi.gateway.loadbalancer.ActiveConnectionTracker;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import com.smartapi.gateway.web.RequestIdFilter;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Forwards one request to one instance and returns its response unchanged, including 4xx/5xx
 * statuses. A backend's 500 is a valid HTTP response that the client should see, not a gateway
 * failure. Gateway errors (502/503/504) are only for when we couldn't get a response at all.
 */
@Component
public class ProxyClient {

    /**
     * Hop-by-hop headers describe one TCP connection (client to gateway, or gateway to backend),
     * not the end-to-end message, so a proxy must not forward them (RFC 9110 section 7.6.1). Host
     * and Content-Length are recomputed by the HTTP client for the new connection.
     */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "host",
            "content-length", "expect");

    private final RestClient restClient;
    private final ActiveConnectionTracker connectionTracker;

    public ProxyClient(RestClient gatewayRestClient, ActiveConnectionTracker connectionTracker) {
        this.restClient = gatewayRestClient;
        this.connectionTracker = connectionTracker;
    }

    /**
     * @throws UpstreamConnectException  connection could not be opened (safe to try another instance)
     * @throws UpstreamTimeoutException  no response within the response timeout (504)
     * @throws BadGatewayException       connection broke / invalid response (502)
     */
    public GatewayResponse forward(BackendInstance target, GatewayRequest request, String downstreamPath) {
        URI uri = targetUri(target, downstreamPath, request.query());
        connectionTracker.acquire(target.id());
        try {
            RestClient.RequestBodySpec spec = restClient.method(HttpMethod.valueOf(request.method()))
                    .uri(uri)
                    .headers(headers -> copyRequestHeaders(request, headers));
            if (request.body() != null && request.body().length > 0) {
                spec.body(request.body());
            }
            // exchange() hands us the raw response and, unlike retrieve(), does not throw on 4xx/5xx.
            return spec.exchange((clientRequest, response) -> new GatewayResponse(
                    response.getStatusCode().value(),
                    copyResponseHeaders(response.getHeaders()),
                    response.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw classify(e, target);
        } finally {
            connectionTracker.release(target.id()); // always, or the count leaks on every failure
        }
    }

    private static URI targetUri(BackendInstance target, String path, String query) {
        try {
            return URI.create(target.baseUrl() + path + (query != null ? "?" + query : ""));
        } catch (IllegalArgumentException e) {
            throw new InvalidGatewayRequestException("Request URI contains illegal characters");
        }
    }

    private static void copyRequestHeaders(GatewayRequest request, HttpHeaders target) {
        request.headers().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower) && !lower.equals("x-request-id") && !lower.startsWith("x-forwarded-")) {
                target.addAll(name, values);
            }
        });
        target.set(RequestIdFilter.HEADER, RequestIdFilter.currentRequestId());
        // Standard proxy headers: the backend sees the gateway as its client, so tell it who the
        // original client was and what host/protocol it used.
        String priorForwardedFor = request.headers().getFirst("X-Forwarded-For");
        target.set("X-Forwarded-For", priorForwardedFor == null
                ? request.clientIp()
                : priorForwardedFor + ", " + request.clientIp());
        String host = request.headers().getFirst(HttpHeaders.HOST);
        if (host != null) {
            target.set("X-Forwarded-Host", host);
        }
        target.set("X-Forwarded-Proto", "http");
    }

    private static HttpHeaders copyResponseHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        source.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower) && !lower.equals("x-request-id")) {
                headers.addAll(name, List.copyOf(values));
            }
        });
        return headers;
    }

    /** Walks the cause chain to find what actually went wrong at the network level. */
    private static RuntimeException classify(ResourceAccessException e, BackendInstance target) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // Order matters: HttpConnectTimeoutException is a subclass of HttpTimeoutException.
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return new UpstreamConnectException(target.address(), cause);
            }
            if (cause instanceof HttpTimeoutException) {
                return new UpstreamTimeoutException(target.address(), cause);
            }
        }
        return new BadGatewayException(target.address(), e);
    }
}
