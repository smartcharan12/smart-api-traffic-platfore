package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * A, B, C, A, B, C, ... One counter per service; request n goes to candidate n mod size.
 * Time O(1), space O(S) for S services.
 *
 * <p>Thread safety comes from {@link AtomicInteger#getAndIncrement()}, a single atomic CPU instruction
 * (compare-and-swap). Every concurrent caller gets a distinct ticket, so even under heavy concurrency
 * the distribution is exact. With a plain {@code int counter++} (read, add, write: three steps), two
 * threads can read the same value and both send their request to the same instance.
 */
@Component
public class RoundRobinStrategy implements LoadBalancingStrategy {

    private final ConcurrentHashMap<Long, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public LoadBalancingStrategyType type() {
        return LoadBalancingStrategyType.ROUND_ROBIN;
    }

    @Override
    public BackendInstance choose(long serviceId, List<BackendInstance> candidates) {
        int ticket = counters.computeIfAbsent(serviceId, id -> new AtomicInteger()).getAndIncrement();
        // floorMod, not %: after 2^31 requests the counter overflows to a negative number and
        // (negative % n) would be a negative index.
        return candidates.get(Math.floorMod(ticket, candidates.size()));
    }
}
