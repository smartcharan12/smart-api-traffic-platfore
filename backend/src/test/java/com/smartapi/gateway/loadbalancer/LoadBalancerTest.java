package com.smartapi.gateway.loadbalancer;

import static com.smartapi.gateway.loadbalancer.Instances.instance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartapi.gateway.entity.LoadBalancingStrategyType;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoadBalancerTest {

    private final ActiveConnectionTracker tracker = new ActiveConnectionTracker();

    @Test
    void dispatchesToTheStrategyConfiguredForTheService() {
        LoadBalancer loadBalancer = new LoadBalancer(List.of(new RoundRobinStrategy(),
                new WeightedRoundRobinStrategy(), new LeastConnectionsStrategy(tracker)));
        List<BackendInstance> candidates = List.of(instance('A', 3), instance('B', 1));

        assertThat(loadBalancer.choose(LoadBalancingStrategyType.WEIGHTED_ROUND_ROBIN, 1, candidates).id()).isEqualTo(1);
        assertThat(loadBalancer.choose(LoadBalancingStrategyType.ROUND_ROBIN, 1, candidates).id()).isEqualTo(1);
        assertThat(loadBalancer.choose(LoadBalancingStrategyType.ROUND_ROBIN, 1, candidates).id()).isEqualTo(2);
    }

    @Test
    void failsFastAtStartupWhenAStrategyIsMissing() {
        assertThatThrownBy(() -> new LoadBalancer(List.of(new RoundRobinStrategy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEIGHTED_ROUND_ROBIN");
    }

    @Test
    void rejectsEmptyCandidateList() {
        LoadBalancer loadBalancer = new LoadBalancer(List.of(new RoundRobinStrategy(),
                new WeightedRoundRobinStrategy(), new LeastConnectionsStrategy(tracker)));
        assertThatThrownBy(() -> loadBalancer.choose(LoadBalancingStrategyType.ROUND_ROBIN, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
