package com.smartapi.gateway.routing;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.service.RegistryChangedEvent;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Service discovery: "which instances exist for this route?" It reports every registered instance;
 * deciding which of them may receive traffic is the HealthFilter's job.
 *
 * <p>Answers come from a small in-memory cache in front of the registry. Profiling (Java Flight
 * Recorder, see docs/phase-2-guide.md) showed that loading the route from PostgreSQL on every request
 * used about 65% of the gateway's CPU, although the registry changes rarely.
 * <ul>
 *   <li>Entries expire after {@code gateway.discovery.cache-ttl} (0 disables the cache).</li>
 *   <li>Any registry change made through THIS gateway clears the cache after the transaction commits,
 *       so marking an instance DOWN takes effect on the next request.</li>
 *   <li>With several gateway instances, a change made through one is seen by the others only when
 *       their entry expires: eventual consistency, bounded by the TTL. Phase 3 can broadcast
 *       invalidations through Redis.</li>
 *   <li>Unknown routes are cached too (negative caching), so a flood of requests to a
 *       non-existent path cannot turn into a flood of queries.</li>
 * </ul>
 *
 * <p>Race handled by the generation counter: request A starts loading from the database, an admin
 * marks an instance DOWN, the commit clears the cache, and THEN A finishes and stores what it read
 * before the change. Clearing alone would let that stale entry live for a full TTL. Each entry
 * remembers the generation it was loaded under; invalidation increments the generation, so an entry
 * loaded before the change can never be served after it.
 */
@Component
public class ServiceDiscovery {

    private record Entry(Optional<DiscoveredService> service, long expiresAtMillis, long generation) {
    }

    private final RegistryLookup registryLookup;
    private final Clock clock;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public ServiceDiscovery(RegistryLookup registryLookup, Clock clock, GatewayProperties properties) {
        this.registryLookup = registryLookup;
        this.clock = clock;
        this.ttlMillis = properties.discovery().cacheTtl().toMillis();
    }

    public Optional<DiscoveredService> find(String basePath) {
        if (ttlMillis <= 0) {
            return registryLookup.load(basePath);
        }
        long now = clock.millis();
        long currentGeneration = generation.get();
        Entry entry = cache.get(basePath);
        if (entry != null && entry.generation() == currentGeneration && now < entry.expiresAtMillis()) {
            return entry.service();
        }
        // On a miss, concurrent requests for the same route may each query the database once. That is
        // bounded (a few queries per TTL) and avoids holding a lock during a database call.
        // The generation is read BEFORE loading: if an invalidation happens while we load, our entry is
        // tagged with the old generation and will be ignored.
        Optional<DiscoveredService> loaded = registryLookup.load(basePath);
        cache.put(basePath, new Entry(loaded, now + ttlMillis, currentGeneration));
        return loaded;
    }

    /**
     * AFTER_COMMIT: if we cleared the cache before the change was committed, a concurrent request
     * could re-read the old data and cache it again for a full TTL.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRegistryChanged(RegistryChangedEvent event) {
        invalidateAll();
    }

    public void invalidateAll() {
        generation.incrementAndGet(); // first: makes every existing entry, and any in-flight load, stale
        cache.clear();                // then free the memory
    }
}
