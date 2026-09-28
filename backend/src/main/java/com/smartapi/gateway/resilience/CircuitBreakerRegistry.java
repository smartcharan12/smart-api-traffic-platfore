package com.smartapi.gateway.resilience;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One circuit breaker per backend ADDRESS (host:port), created on first use. Keyed by address rather
 * than by registry id because the same process can be registered under several services; if that
 * process is down, every route to it should see the open circuit.
 *
 * <p>Breaker state is local to each gateway process (like Hystrix and Resilience4j). Each gateway
 * learns from its own calls, which reacts fastest and needs no shared store in the request path.
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);
    private static final int RECENT_TRANSITIONS = 200;

    private final boolean enabled;
    private final CircuitBreakerConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Deque<CircuitBreaker.Transition> recent = new ArrayDeque<>();
    private final List<Consumer<CircuitBreaker.Transition>> listeners = new CopyOnWriteArrayList<>();

    public CircuitBreakerRegistry(GatewayProperties properties, Clock clock) {
        GatewayProperties.CircuitBreaker cb = properties.circuitBreaker();
        this.enabled = cb.enabled();
        this.config = new CircuitBreakerConfig(cb.slidingWindowSize(), cb.minimumCalls(), cb.failureRateThreshold(),
                cb.openDuration(), cb.halfOpenPermittedCalls());
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CircuitBreaker forInstance(BackendInstance instance) {
        return breakers.computeIfAbsent(instance.address(),
                address -> new CircuitBreaker(address, config, clock, this::onTransition));
    }

    /** e.g. the metrics component registers here to count openings. */
    public void addListener(Consumer<CircuitBreaker.Transition> listener) {
        listeners.add(listener);
    }

    public List<CircuitBreaker.Snapshot> snapshots() {
        return breakers.values().stream().map(CircuitBreaker::snapshot)
                .sorted(Comparator.comparing(CircuitBreaker.Snapshot::name)).toList();
    }

    /** Most recent first. */
    public List<CircuitBreaker.Transition> recentTransitions() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    private void onTransition(CircuitBreaker.Transition transition) {
        if (transition.to() == CircuitState.OPEN) {
            log.warn("Circuit {} {} -> {}: {}", transition.name(), transition.from(), transition.to(), transition.reason());
        } else {
            log.info("Circuit {} {} -> {}: {}", transition.name(), transition.from(), transition.to(), transition.reason());
        }
        synchronized (recent) {
            recent.addFirst(transition);
            if (recent.size() > RECENT_TRANSITIONS) {
                recent.removeLast();
            }
        }
        listeners.forEach(listener -> listener.accept(transition));
    }
}
