package com.smartapi.gateway.loadbalancer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Number of in-flight requests the gateway currently has open to each instance. The proxy calls
 * {@link #acquire} before forwarding and {@link #release} in a {@code finally} block, so the count
 * goes back down even when the backend call throws. Forgetting the finally would leak a
 * "connection" on every failure, and least-connections would slowly stop choosing that instance.
 */
@Component
public class ActiveConnectionTracker {

    private final ConcurrentHashMap<Long, AtomicInteger> active = new ConcurrentHashMap<>();

    public void acquire(long instanceId) {
        active.computeIfAbsent(instanceId, id -> new AtomicInteger()).incrementAndGet();
    }

    public void release(long instanceId) {
        AtomicInteger count = active.get(instanceId);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    public int activeConnections(long instanceId) {
        AtomicInteger count = active.get(instanceId);
        return count == null ? 0 : count.get();
    }

    public Map<Long, Integer> snapshot() {
        return active.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
