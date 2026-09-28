package com.smartapi.gateway.routing;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.exception.GatewayException;
import com.smartapi.gateway.exception.NoHealthyInstanceException;
import com.smartapi.gateway.exception.RateLimitExceededException;
import com.smartapi.gateway.exception.RouteNotFoundException;
import com.smartapi.gateway.exception.UpstreamUnavailableException;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import com.smartapi.gateway.loadbalancer.LoadBalancer;
import com.smartapi.gateway.ratelimit.RateLimitDecision;
import com.smartapi.gateway.ratelimit.RateLimitKeyResolver;
import com.smartapi.gateway.ratelimit.RateLimiter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * The gateway pipeline. Each step is one collaborator, called in this order:
 * <pre>
 *  1. RouteResolver      /gateway/orders/orders/1 -> service "/orders", path "/orders/1"   (400 if malformed)
 *  2. RateLimiter        may this client send another request?                             (429)
 *  3. ServiceDiscovery   which instances does "/orders" have?                              (404 if no service)
 *  4. HealthFilter       drop DOWN instances                                               (503 if none left)
 *  5. LoadBalancer       pick one instance with the service's strategy
 *  6. ProxyClient        forward and return the instance's response                        (502 / 504)
 *     - connection refused -> mark that instance as tried, go back to 5 with the rest     (503 if all fail)
 *  7. GatewayAccessLog   one structured log line, whatever happened
 * </pre>
 * Rate limiting runs before discovery on purpose: a rejected request costs a map lookup instead of two
 * SQL queries, so a flood of requests cannot turn into a flood of database load.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final RouteResolver routeResolver;
    private final RateLimiter rateLimiter;
    private final RateLimitKeyResolver rateLimitKeyResolver;
    private final ServiceDiscovery serviceDiscovery;
    private final HealthFilter healthFilter;
    private final LoadBalancer loadBalancer;
    private final ProxyClient proxyClient;
    private final int maxConnectAttempts;

    public GatewayService(RouteResolver routeResolver, RateLimiter rateLimiter,
                          RateLimitKeyResolver rateLimitKeyResolver, ServiceDiscovery serviceDiscovery,
                          HealthFilter healthFilter, LoadBalancer loadBalancer, ProxyClient proxyClient,
                          GatewayProperties properties) {
        this.routeResolver = routeResolver;
        this.rateLimiter = rateLimiter;
        this.rateLimitKeyResolver = rateLimitKeyResolver;
        this.serviceDiscovery = serviceDiscovery;
        this.healthFilter = healthFilter;
        this.loadBalancer = loadBalancer;
        this.proxyClient = proxyClient;
        this.maxConnectAttempts = properties.proxy().maxConnectAttempts();
    }

    public GatewayResponse handle(GatewayRequest request) {
        GatewayAccessLog accessLog = new GatewayAccessLog(request);
        try {
            Route route = routeResolver.resolve(request.path());

            String key = rateLimitKeyResolver.resolve(request.clientIp(), request.userId(), request.method(),
                    route.basePath());
            RateLimitDecision decision = rateLimiter.tryAcquire(key);
            accessLog.rateLimit(decision);
            if (!decision.allowed()) {
                throw new RateLimitExceededException(decision);
            }

            DiscoveredService service = serviceDiscovery.find(route.basePath())
                    .orElseThrow(() -> new RouteNotFoundException(route.basePath()));
            accessLog.service(service.name());

            List<BackendInstance> routable = healthFilter.routable(service.instances());
            if (routable.isEmpty()) {
                throw new NoHealthyInstanceException(service.name(), service.instances().size());
            }

            GatewayResponse response = forwardWithFailover(request, route, service, routable, accessLog);
            addRateLimitHeaders(response.headers(), decision);
            accessLog.status(response.status());
            return response;
        } catch (GatewayException e) {
            accessLog.status(e.getStatus().value());
            accessLog.error(e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            accessLog.status(500);
            accessLog.error(e.getClass().getSimpleName());
            throw e;
        } finally {
            accessLog.write();
        }
    }

    /**
     * Tries up to {@code maxConnectAttempts} different instances, but only moves on when the connection
     * could not be opened. A timeout or broken response is NOT retried here, because the backend
     * may already have acted on the request.
     */
    private GatewayResponse forwardWithFailover(GatewayRequest request, Route route, DiscoveredService service,
                                                List<BackendInstance> routable, GatewayAccessLog accessLog) {
        Set<Long> refused = new HashSet<>();
        int attempts = Math.min(maxConnectAttempts, routable.size());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            List<BackendInstance> candidates = refused.isEmpty()
                    ? routable
                    : routable.stream().filter(instance -> !refused.contains(instance.id())).toList();
            BackendInstance target = loadBalancer.choose(service.strategy(), service.id(), candidates);
            accessLog.attempt(target.address());
            try {
                return proxyClient.forward(target, request, route.downstreamPath());
            } catch (UpstreamConnectException e) {
                log.warn("Connection to {} failed (attempt {}/{}): {}", target.address(), attempt, attempts,
                        e.getCause().getClass().getSimpleName());
                refused.add(target.id());
            }
        }
        throw new UpstreamUnavailableException(service.name(), refused.size());
    }

    private static void addRateLimitHeaders(HttpHeaders headers, RateLimitDecision decision) {
        if (decision.isLimited()) {
            headers.set("X-RateLimit-Limit", String.valueOf(decision.limit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
    }
}
