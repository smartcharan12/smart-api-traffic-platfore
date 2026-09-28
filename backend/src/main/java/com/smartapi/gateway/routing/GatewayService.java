package com.smartapi.gateway.routing;

import com.smartapi.gateway.exception.GatewayException;
import com.smartapi.gateway.exception.NoHealthyInstanceException;
import com.smartapi.gateway.exception.RateLimitExceededException;
import com.smartapi.gateway.exception.RouteNotFoundException;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import com.smartapi.gateway.ratelimit.RateLimitDecision;
import com.smartapi.gateway.ratelimit.RateLimitKeyResolver;
import com.smartapi.gateway.ratelimit.RateLimiter;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * The gateway pipeline. Each step is one collaborator, called in this order:
 * <pre>
 *  1. RouteResolver      /gateway/orders/orders/1 -> service "/orders", path "/orders/1"   (400 if malformed)
 *  2. RateLimiter        may this client send another request?                             (429)
 *  3. ServiceDiscovery   which instances does "/orders" have?                              (404 if no service)
 *  4. HealthFilter       drop DOWN instances                                               (503 if none left)
 *  5. ResilientForwarder circuit breaker -> load balancer -> proxy, with retries          (503 circuit open)
 *                        returns the backend's response, or 502 / 503 / 504 when none was obtained
 *  6. GatewayAccessLog   one structured log line, whatever happened
 * </pre>
 * Rate limiting runs before discovery on purpose: a rejected request costs a map lookup instead of two
 * SQL queries, so a flood of requests cannot turn into a flood of database load.
 */
@Service
public class GatewayService {

    private final RouteResolver routeResolver;
    private final RateLimiter rateLimiter;
    private final RateLimitKeyResolver rateLimitKeyResolver;
    private final ServiceDiscovery serviceDiscovery;
    private final HealthFilter healthFilter;
    private final ResilientForwarder forwarder;

    public GatewayService(RouteResolver routeResolver, RateLimiter rateLimiter,
                          RateLimitKeyResolver rateLimitKeyResolver, ServiceDiscovery serviceDiscovery,
                          HealthFilter healthFilter, ResilientForwarder forwarder) {
        this.routeResolver = routeResolver;
        this.rateLimiter = rateLimiter;
        this.rateLimitKeyResolver = rateLimitKeyResolver;
        this.serviceDiscovery = serviceDiscovery;
        this.healthFilter = healthFilter;
        this.forwarder = forwarder;
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

            GatewayResponse response = forwarder.forward(request, route, service, routable, accessLog);
            addRateLimitHeaders(response.headers(), decision);
            response.headers().set("X-Retry-Count", String.valueOf(accessLog.retries()));
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

    private static void addRateLimitHeaders(HttpHeaders headers, RateLimitDecision decision) {
        if (decision.isLimited()) {
            headers.set("X-RateLimit-Limit", String.valueOf(decision.limit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
    }
}
