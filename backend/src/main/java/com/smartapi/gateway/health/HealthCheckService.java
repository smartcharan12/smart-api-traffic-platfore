package com.smartapi.gateway.health;

import com.smartapi.gateway.config.GatewayProperties;
import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.entity.ServiceInstance;
import com.smartapi.gateway.redis.LeaderLock;
import com.smartapi.gateway.repository.ServiceInstanceRepository;
import com.smartapi.gateway.service.ServiceInstanceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * Active health checking. Every {@code interval} (only on the gateway that holds the leader lock):
 * <ol>
 *   <li>load all instances from the registry;</li>
 *   <li>probe them IN PARALLEL on a fixed thread pool (GET host:port + service health path, with a
 *       timeout), so one hung instance delays nobody;</li>
 *   <li>feed each result to that instance's {@link InstanceHealth} (HEALTHY / DEGRADED / DOWN rules);</li>
 *   <li>write status changes through ServiceInstanceService (which refreshes routing caches on every
 *       gateway) and record heartbeats for instances that answered, in one batched UPDATE.</li>
 * </ol>
 */
@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    /** What a probe needs, copied from the entity so worker threads never touch JPA objects. */
    record Target(long instanceId, String serviceName, String address, URI uri, InstanceStatus dbStatus) {
    }

    record ProbeResult(boolean success, long latencyMs, String error) {
    }

    public record StatusChange(long instanceId, String address, InstanceStatus from, InstanceStatus to) {
    }

    private final ServiceInstanceRepository instanceRepository;
    private final ServiceInstanceService instanceService;
    private final LeaderLock leaderLock;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final GatewayProperties.HealthCheck config;
    private final HealthEvaluator.Thresholds thresholds;
    private final HttpClient probeClient;
    private final ExecutorService probePool;
    private final Map<Long, InstanceHealth> health = new ConcurrentHashMap<>();
    private volatile boolean leader;

    public HealthCheckService(ServiceInstanceRepository instanceRepository, ServiceInstanceService instanceService,
                              LeaderLock healthCheckLeaderLock, TaskScheduler scheduler, Clock clock,
                              GatewayProperties properties) {
        this.instanceRepository = instanceRepository;
        this.instanceService = instanceService;
        this.leaderLock = healthCheckLeaderLock;
        this.scheduler = scheduler;
        this.clock = clock;
        this.config = properties.healthCheck();
        this.thresholds = new HealthEvaluator.Thresholds(config.degradedLatency().toMillis(),
                config.unhealthyThreshold(), config.healthyThreshold());
        this.probeClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.timeout())
                .build();
        AtomicInteger threadNumber = new AtomicInteger();
        // Fixed-size pool: at most `threads` probes run at once however many instances exist, and the
        // threads are reused every cycle instead of created per probe. Daemon threads don't block JVM exit.
        this.probePool = Executors.newFixedThreadPool(config.threads(), runnable -> {
            Thread thread = new Thread(runnable, "health-probe-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void schedule() {
        if (config.enabled()) {
            scheduler.scheduleWithFixedDelay(this::runScheduled, Instant.now().plus(config.initialDelay()),
                    config.interval());
            log.info("Health checks every {} (leader-elected across gateways)", config.interval());
        }
    }

    private void runScheduled() {
        try {
            runChecks();
        } catch (RuntimeException e) {
            // Never let an exception kill the scheduled task: log and try again next cycle.
            log.error("Health check cycle failed", e);
        }
    }

    /** One full cycle. Public (and synchronized) so tests and demos can trigger it deterministically. */
    public synchronized List<StatusChange> runChecks() {
        leader = leaderLock.acquireOrRenew();
        if (!leader) {
            return List.of();
        }
        List<Target> targets = instanceRepository.findAllWithService().stream().map(this::toTarget).toList();
        Set<Long> current = targets.stream().map(Target::instanceId).collect(Collectors.toSet());
        health.keySet().retainAll(current);   // forget deleted instances

        List<Future<ProbeResult>> futures = targets.stream().map(t -> probePool.submit(() -> probe(t))).toList();
        List<StatusChange> changes = new ArrayList<>();
        List<Long> heartbeats = new ArrayList<>();
        Instant now = clock.instant();
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            ProbeResult result = await(futures.get(i));
            InstanceHealth record = health.computeIfAbsent(target.instanceId(),
                    id -> new InstanceHealth(id, target.serviceName(), target.address(), target.dbStatus()));
            if (record.status() != target.dbStatus()) {
                record.overrideStatus(target.dbStatus()); // someone changed it by hand (PATCH) since last cycle
            }
            InstanceStatus next = record.record(result.success(), result.latencyMs(), result.error(), now, thresholds);
            if (result.success()) {
                heartbeats.add(target.instanceId());
            }
            if (next != target.dbStatus()) {
                changes.add(new StatusChange(target.instanceId(), target.address(), target.dbStatus(), next));
            }
        }
        instanceService.recordHeartbeats(heartbeats, now);
        for (StatusChange change : changes) {
            log.warn("Instance {} {} -> {}", change.address(), change.from(), change.to());
            instanceService.updateStatus(change.instanceId(), change.to());
        }
        return changes;
    }

    public List<InstanceHealth.Snapshot> snapshots() {
        return health.values().stream().map(InstanceHealth::snapshot).toList();
    }

    public boolean isLeader() {
        return leader;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    private Target toTarget(ServiceInstance instance) {
        String address = instance.getHost() + ":" + instance.getPort();
        return new Target(instance.getId(), instance.getService().getName(), address,
                URI.create("http://" + address + instance.getService().getHealthCheckPath()), instance.getStatus());
    }

    private ProbeResult probe(Target target) {
        long start = System.nanoTime();
        try {
            HttpResponse<Void> response = probeClient.send(
                    HttpRequest.newBuilder(target.uri()).timeout(config.timeout()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            long latency = (System.nanoTime() - start) / 1_000_000;
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new ProbeResult(ok, latency, ok ? null : "HTTP " + response.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ProbeResult(false, (System.nanoTime() - start) / 1_000_000, e.getClass().getSimpleName());
        }
    }

    private ProbeResult await(Future<ProbeResult> future) {
        try {
            // The probe has its own timeout; this is a backstop against a stuck worker.
            return future.get(config.timeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new ProbeResult(false, config.timeout().toMillis(), "TimeoutException");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, 0, "Interrupted");
        } catch (ExecutionException e) {
            return new ProbeResult(false, 0, e.getCause().getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        probePool.shutdownNow();
    }

    Collection<InstanceHealth> records() {
        return health.values();
    }
}
