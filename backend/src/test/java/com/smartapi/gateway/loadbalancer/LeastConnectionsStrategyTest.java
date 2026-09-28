package com.smartapi.gateway.loadbalancer;

import static com.smartapi.gateway.loadbalancer.Instances.instance;
import static com.smartapi.gateway.loadbalancer.Instances.name;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LeastConnectionsStrategyTest {

    private final ActiveConnectionTracker tracker = new ActiveConnectionTracker();
    private final LeastConnectionsStrategy strategy = new LeastConnectionsStrategy(tracker);
    private final BackendInstance a = instance('A', 1);
    private final BackendInstance b = instance('B', 1);
    private final BackendInstance c = instance('C', 1);
    private final List<BackendInstance> abc = List.of(a, b, c);

    @Test
    void picksTheInstanceWithFewestActiveConnections() {
        tracker.acquire(a.id());
        tracker.acquire(a.id());
        tracker.acquire(b.id());
        for (int i = 0; i < 5; i++) {
            assertThat(strategy.choose(1, abc)).isEqualTo(c); // C has 0, regardless of rotation
        }
    }

    @Test
    void tiesAreSpreadInsteadOfAlwaysPickingTheFirst() {
        // All counts are 0 (sequential traffic). Without rotation this would be "AAAAAA".
        String picks = IntStream.range(0, 6).mapToObj(i -> name(strategy.choose(1, abc))).collect(Collectors.joining());
        assertThat(picks).isEqualTo("ABCABC");
    }

    @Test
    void anInstanceHoldingSlowRequestsStopsReceivingNewOnes() {
        // Simulate: every request sent to A is still in flight (A is slow); B and C finish immediately.
        int toA = 0;
        for (int i = 0; i < 30; i++) {
            BackendInstance chosen = strategy.choose(1, abc);
            if (chosen.equals(a)) {
                tracker.acquire(a.id()); // never released
                toA++;
            }
        }
        assertThat(toA).isEqualTo(1); // after its first request, A always has more connections than B and C
    }

    @Test
    void trackerCountsGoUpAndDown() {
        tracker.acquire(a.id());
        tracker.acquire(a.id());
        tracker.release(a.id());
        assertThat(tracker.activeConnections(a.id())).isEqualTo(1);
        assertThat(tracker.activeConnections(b.id())).isZero();
    }
}
