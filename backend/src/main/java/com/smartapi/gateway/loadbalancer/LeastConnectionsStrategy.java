package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Picks the candidate with the fewest in-flight requests. It adapts to instances of different
 * speed: a slow instance holds its connections longer, so its count stays higher and it receives
 * less new traffic. Round robin would keep sending it an equal share.
 *
 * <p>Ties are common: with light traffic every count is 0. If ties always went to the first
 * candidate, one instance would get all sequential traffic. So the scan starts at a rotating offset,
 * which spreads ties round-robin.
 *
 * <p>Deliberate race: two threads can read the same counts at the same moment and both pick the same
 * instance before either increments. Preventing that needs a lock around "read all counts + pick +
 * increment", which would serialise every request through the gateway. The cost of the race is a
 * slightly uneven pick; the counters themselves stay exact (AtomicInteger). We accept it.
 *
 * <p>Time O(n) per pick; space O(n) for the counters.
 */
@Component
public class LeastConnectionsStrategy implements LoadBalancingStrategy {

    private final ActiveConnectionTracker tracker;
    private final AtomicInteger rotation = new AtomicInteger();

    public LeastConnectionsStrategy(ActiveConnectionTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public LoadBalancingStrategyType type() {
        return LoadBalancingStrategyType.LEAST_CONNECTIONS;
    }

    @Override
    public BackendInstance choose(long serviceId, List<BackendInstance> candidates) {
        int size = candidates.size();
        int start = Math.floorMod(rotation.getAndIncrement(), size);
        BackendInstance best = null;
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            BackendInstance candidate = candidates.get((start + i) % size);
            int active = tracker.activeConnections(candidate.id());
            if (active < fewest) { // strictly less: the first of equal candidates (in rotated order) wins
                best = candidate;
                fewest = active;
            }
        }
        return best;
    }
}
