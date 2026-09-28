package com.smartapi.gateway.loadbalancer;

import static com.smartapi.gateway.loadbalancer.Instances.instance;
import static com.smartapi.gateway.loadbalancer.Instances.name;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RoundRobinStrategyTest {

    private final RoundRobinStrategy strategy = new RoundRobinStrategy();
    private final List<BackendInstance> abc = List.of(instance('A', 1), instance('B', 1), instance('C', 1));

    @Test
    void cyclesThroughInstancesInOrder() {
        String sequence = IntStream.range(0, 7).mapToObj(i -> name(strategy.choose(1, abc)))
                .reduce("", String::concat);
        assertThat(sequence).isEqualTo("ABCABCA");
    }

    @Test
    void servicesHaveIndependentCounters() {
        strategy.choose(1, abc);
        strategy.choose(1, abc);
        assertThat(name(strategy.choose(2, abc))).isEqualTo("A"); // service 2 starts at its own beginning
    }

    @Test
    void ignoresWeights() {
        List<BackendInstance> weighted = List.of(instance('A', 5), instance('B', 1));
        long a = IntStream.range(0, 100).filter(i -> name(strategy.choose(1, weighted)).equals("A")).count();
        assertThat(a).isEqualTo(50);
    }

    @Test
    void keepsWorkingWhenTheCandidateListShrinks() {
        strategy.choose(1, abc);
        strategy.choose(1, abc);                         // counter is now 2
        List<BackendInstance> ac = List.of(instance('A', 1), instance('C', 1)); // B marked DOWN
        assertThat(name(strategy.choose(1, ac))).isEqualTo("A"); // 2 mod 2 = 0: still a valid index
        assertThat(name(strategy.choose(1, ac))).isEqualTo("C");
    }
}
