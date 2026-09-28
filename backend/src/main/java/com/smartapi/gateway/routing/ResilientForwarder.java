package com.smartapi.gateway.routing;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.exception.BadGatewayException;
import com.smartapi.gateway.exception.CircuitOpenException;
import com.smartapi.gateway.exception.GatewayException;
import com.smartapi.gateway.exception.UpstreamTimeoutException;
import com.smartapi.gateway.exception.UpstreamUnavailableException;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import com.smartapi.gateway.loadbalancer.LoadBalancer;
import com.smartapi.gateway.metrics.GatewayMetrics;
import com.smartapi.gateway.resilience.CircuitBreaker;
import com.smartapi.gateway.resilience.CircuitBreakerRegistry;
import com.smartapi.gateway.resilience.RetryBudget;
import com.smartapi.gateway.resilience.RetryDecider;
import com.smartapi.gateway.resilience.RetryDecider.FailureKind;
import com.smartapi.gateway.resilience.RetryPolicy;
import com.smartapi.gateway.resilience.Sleeper;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends one client request to the backend, with circuit breakers and retries:
 * <pre>
 * for attempt = 1..maxAttempts:
 *     candidates = healthy instances, preferring ones not yet tried for this request
 *     drop candidates whose circuit is OPEN               (all open on attempt 1 -> 503 + Retry-After)
 *     choose one with the service's load-balancing strategy; take a circuit permit
 *     forward
 *       success (incl. 4xx)            -> record success, return
 *       failure (refused, timeout, 502,
 *                or a 5xx response)     -> record failure on that instance's circuit
 *     retry only if: attempts remain, the failure is transient, the request is safe to repeat,
 *                    and the retry budget has a token          -> back off (with jitter), loop
 * nothing left: return the last backend response, or throw 503 / 504 / 502
 * </pre>
 */
@Component
public class ResilientForwarder {

    private static final Logger log = LoggerFactory.getLogger(ResilientForwarder.class);

    private final LoadBalancer loadBalancer;
    private final ProxyClient proxyClient;
    private final CircuitBreakerRegistry breakers;
    private final RetryPolicy retryPolicy;
    private final RetryBudget retryBudget;
    private final RetryDecider retryDecider;
    private final Sleeper sleeper;
    private final DoubleSupplier jitterRandom;
    private final Set<Integer> failureStatuses;
    private final GatewayMetrics metrics;

    public ResilientForwarder(LoadBalancer loadBalancer, ProxyClient proxyClient, CircuitBreakerRegistry breakers,
                              RetryPolicy retryPolicy, RetryBudget retryBudget, RetryDecider retryDecider,
                              Sleeper retrySleeper, DoubleSupplier jitterRandom, GatewayProperties properties,
                              GatewayMetrics metrics) {
        this.loadBalancer = loadBalancer;
        this.proxyClient = proxyClient;
        this.breakers = breakers;
        this.retryPolicy = retryPolicy;
        this.retryBudget = retryBudget;
        this.retryDecider = retryDecider;
        this.sleeper = retrySleeper;
        this.jitterRandom = jitterRandom;
        this.failureStatuses = Set.copyOf(properties.circuitBreaker().failureStatuses());
        this.metrics = metrics;
    }

    public GatewayResponse forward(GatewayRequest request, Route route, DiscoveredService service,
                                   List<BackendInstance> routable, GatewayAccessLog trace) {
        retryBudget.recordRequest();
        Set<Long> tried = new HashSet<>();
        GatewayResponse lastResponse = null;
        RuntimeException lastError = null;
        int permitRaces = 0;

        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            List<BackendInstance> permitted = permittedCandidates(routable, tried);
            if (permitted.isEmpty()) {
                if (attempt == 1) {
                    throw new CircuitOpenException(service.name(), shortestRemainingOpen(routable));
                }
                break;
            }
            BackendInstance target = loadBalancer.choose(service.strategy(), service.id(), permitted);
            CircuitBreaker breaker = breakers.isEnabled() ? breakers.forInstance(target) : null;
            if (breaker != null && !breaker.tryAcquirePermission()) {
                // Another request took the last half-open trial slot between our check and now.
                tried.add(target.id());
                if (++permitRaces <= routable.size()) {
                    attempt--;
                }
                continue;
            }

            trace.attempt(target.address());
            trace.ids(null, target.id());
            FailureKind failure;
            try {
                GatewayResponse response = proxyClient.forward(target, request, route.downstreamPath());
                if (!failureStatuses.contains(response.status())) {
                    if (breaker != null) {
                        breaker.onSuccess();
                    }
                    return response;
                }
                failure = RetryDecider.classifyStatus(response.status());
                lastResponse = response;
                lastError = null;
            } catch (UpstreamConnectException e) {
                failure = FailureKind.CONNECT_FAILED;
                lastError = e;
                lastResponse = null;
            } catch (UpstreamTimeoutException e) {
                failure = FailureKind.TIMEOUT;
                lastError = e;
                lastResponse = null;
            } catch (BadGatewayException e) {
                failure = FailureKind.BAD_GATEWAY;
                lastError = e;
                lastResponse = null;
            } catch (RuntimeException e) {
                if (breaker != null) {
                    breaker.onFailure(); // never leave a half-open permit dangling
                }
                throw e;
            }
            if (breaker != null) {
                breaker.onFailure();
            }
            tried.add(target.id());
            trace.failure(failure.name() + "@" + target.address());

            if (attempt == retryPolicy.maxAttempts()
                    || !retryDecider.isRetryable(failure, request.method(), request.headers())) {
                break;
            }
            if (!retryBudget.tryWithdraw()) {
                trace.failure("RETRY_BUDGET_EXHAUSTED");
                break;
            }
            Duration delay = retryPolicy.backoff(attempt, jitterRandom);
            trace.retry();
            metrics.retry(service.name(), failure.name());
            log.debug("Retrying {} after {} ({} on {})", route.basePath(), delay, failure, target.address());
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (lastResponse != null) {
            return lastResponse; // e.g. the backend's own 503/500: pass it through unchanged
        }
        if (lastError instanceof UpstreamConnectException || lastError == null) {
            throw new UpstreamUnavailableException(service.name(), trace.attempts());
        }
        throw (GatewayException) lastError;
    }

    /**
     * Healthy instances whose circuit allows a call, preferring instances this request hasn't tried.
     * With a single instance (or all already tried) we retry the same instances rather than give up.
     */
    private List<BackendInstance> permittedCandidates(List<BackendInstance> routable, Set<Long> tried) {
        List<BackendInstance> untried = routable.stream().filter(i -> !tried.contains(i.id())).toList();
        List<BackendInstance> pool = untried.isEmpty() ? routable : untried;
        List<BackendInstance> permitted = pool.stream().filter(this::circuitAllows).toList();
        if (permitted.isEmpty() && pool != routable) {
            permitted = routable.stream().filter(this::circuitAllows).toList();
        }
        return permitted;
    }

    private boolean circuitAllows(BackendInstance instance) {
        return !breakers.isEnabled() || breakers.forInstance(instance).isCallPermitted();
    }

    private Duration shortestRemainingOpen(List<BackendInstance> routable) {
        return routable.stream()
                .map(i -> breakers.forInstance(i).snapshot().remainingOpen())
                .min(Duration::compareTo)
                .orElse(Duration.ofSeconds(1));
    }
}
