package com.smartapi.gateway.health;

import static com.smartapi.gateway.entity.InstanceStatus.DEGRADED;
import static com.smartapi.gateway.entity.InstanceStatus.DOWN;
import static com.smartapi.gateway.entity.InstanceStatus.HEALTHY;
import static com.smartapi.gateway.entity.InstanceStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.entity.InstanceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** degraded above 500 ms, DOWN after 3 failures, back up after 2 successes. */
class HealthEvaluatorTest {

    private final HealthEvaluator.Thresholds t = new HealthEvaluator.Thresholds(500, 3, 2);
    private final Instant start = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void fastSuccessIsHealthySlowSuccessIsDegraded() {
        assertThat(HealthEvaluator.next(UNKNOWN, true, 20, 1, 0, t)).isEqualTo(HEALTHY);
        assertThat(HealthEvaluator.next(HEALTHY, true, 800, 5, 0, t)).isEqualTo(DEGRADED);
    }

    @Test
    void instanceGoesHealthyDegradedDownAndBackWithHysteresis() {
        InstanceHealth health = new InstanceHealth(1, "orders-service", "localhost:9002", UNKNOWN);
        List<InstanceStatus> path = new ArrayList<>();
        boolean[] probes = {true, false, false, false, false, true, true, true};
        for (int i = 0; i < probes.length; i++) {
            path.add(health.record(probes[i], 10, probes[i] ? null : "ConnectException",
                    start.plusSeconds(5L * i), t));
        }
        assertThat(path).containsExactly(
                HEALTHY,   // ok
                DEGRADED,  // 1st failure: suspicious, still routable
                DEGRADED,  // 2nd
                DOWN,      // 3rd consecutive failure
                DOWN,      // stays down
                DOWN,      // 1st success after DOWN: not yet trusted
                HEALTHY,   // 2nd consecutive success
                HEALTHY);
    }

    @Test
    void tracksAvailabilityUptimeAndHeartbeat() {
        InstanceHealth health = new InstanceHealth(1, "orders-service", "localhost:9001", UNKNOWN);
        health.record(true, 12, null, start, t);
        health.record(false, 2000, "HTTP 500", start.plusSeconds(5), t);
        health.record(true, 15, null, start.plusSeconds(10), t);
        health.record(true, 11, null, start.plusSeconds(15), t);
        InstanceHealth.Snapshot s = health.snapshot();
        assertThat(s.availabilityPercent()).isEqualTo(75.0);
        assertThat(s.uptimeSeconds(start.plus(Duration.ofSeconds(60)))).isEqualTo(60);  // never went DOWN
        assertThat(s.lastSuccessAt()).isEqualTo(start.plusSeconds(15));
        assertThat(s.lastLatencyMs()).isEqualTo(11);
        assertThat(s.lastError()).isNull();
    }

    @Test
    void uptimeRestartsAfterBeingDown() {
        InstanceHealth health = new InstanceHealth(1, "s", "a", HEALTHY);
        for (int i = 0; i < 3; i++) {
            health.record(false, 5, "ConnectException", start.plusSeconds(i), t);
        }
        assertThat(health.snapshot().upSince()).isNull();
        health.record(true, 5, null, start.plusSeconds(10), t);   // still DOWN (1 of 2 required successes)
        assertThat(health.snapshot().upSince()).isNull();
        health.record(true, 5, null, start.plusSeconds(15), t);   // HEALTHY: routable again from here
        assertThat(health.snapshot().upSince()).isEqualTo(start.plusSeconds(15));
    }
}
