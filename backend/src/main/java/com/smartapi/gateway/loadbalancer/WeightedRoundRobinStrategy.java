package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Smooth weighted round robin (the algorithm nginx uses). Weights A=3, B=1 give A, A, B, A, ...
 * rather than the naive A, A, A, B, which sends bursts to the heavy instance.
 *
 * <p>Each instance has a "current weight", initially 0. For every pick:
 * <ol>
 *   <li>add each instance's configured weight to its current weight;</li>
 *   <li>pick the instance with the highest current weight;</li>
 *   <li>subtract the total of all weights from the picked instance.</li>
 * </ol>
 * Over any {@code totalWeight} consecutive picks (with an unchanged candidate set), each instance is
 * picked exactly {@code weight} times.
 *
 * <p>Time O(n) per pick for n instances; space O(n) per service.
 *
 * <p>Thread safety: a pick reads and updates several numbers that must change together, which a
 * single atomic variable cannot do, so each service's state is guarded by its own lock
 * ({@code synchronized} on that service's map). Different services never block each other. The critical
 * section is a short loop with no I/O, so holding the lock costs little.
 */
@Component
public class WeightedRoundRobinStrategy implements LoadBalancingStrategy {

    private final ConcurrentHashMap<Long, Map<Long, Integer>> currentWeightsByService = new ConcurrentHashMap<>();

    @Override
    public LoadBalancingStrategyType type() {
        return LoadBalancingStrategyType.WEIGHTED_ROUND_ROBIN;
    }

    @Override
    public BackendInstance choose(long serviceId, List<BackendInstance> candidates) {
        Map<Long, Integer> current = currentWeightsByService.computeIfAbsent(serviceId, id -> new HashMap<>());
        synchronized (current) {
            int totalWeight = 0;
            BackendInstance best = null;
            int bestCurrent = Integer.MIN_VALUE;
            for (BackendInstance candidate : candidates) {
                int updated = current.merge(candidate.id(), candidate.weight(), Integer::sum);
                totalWeight += candidate.weight();
                if (updated > bestCurrent) {
                    best = candidate;
                    bestCurrent = updated;
                }
            }
            current.merge(best.id(), -totalWeight, Integer::sum);
            // Forget instances that are no longer candidates (removed or marked DOWN), so a
            // returning instance starts fresh instead of with a stale, possibly huge, current weight.
            if (current.size() > candidates.size()) {
                Set<Long> ids = new HashSet<>();
                candidates.forEach(c -> ids.add(c.id()));
                current.keySet().retainAll(ids);
            }
            return best;
        }
    }
}
