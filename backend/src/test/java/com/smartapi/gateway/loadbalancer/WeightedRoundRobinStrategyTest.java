package com.smartapi.gateway.loadbalancer;

import static com.smartapi.gateway.loadbalancer.Instances.instance;
import static com.smartapi.gateway.loadbalancer.Instances.name;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WeightedRoundRobinStrategyTest {

    private final WeightedRoundRobinStrategy strategy = new WeightedRoundRobinStrategy();

    private String sequence(List<BackendInstance> candidates, int picks) {
        return IntStream.range(0, picks).mapToObj(i -> name(strategy.choose(1, candidates)))
                .collect(Collectors.joining());
    }

    @Test
    void weights3And1GiveASmoothSequence() {
        // Traced by hand in docs/phase-2-guide.md. Naive WRR would give AAAB AAAB.
        assertThat(sequence(List.of(instance('A', 3), instance('B', 1)), 8)).isEqualTo("AABAAABA");
    }

    @Test
    void weights5_1_1MatchNginxReferenceSequence() {
        // The example from nginx's commit introducing smooth weighted round robin.
        assertThat(sequence(List.of(instance('A', 5), instance('B', 1), instance('C', 1)), 7)).isEqualTo("AABACAA");
    }

    @Test
    void everyWindowOfTotalWeightPicksContainsEachInstanceExactlyWeightTimes() {
        List<BackendInstance> candidates = List.of(instance('A', 3), instance('B', 2), instance('C', 1));
        for (int cycle = 0; cycle < 50; cycle++) {
            Map<String, Long> counts = sequence(candidates, 6).chars().mapToObj(c -> String.valueOf((char) c))
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("A", 3L, "B", 2L, "C", 1L));
        }
    }

    @Test
    void equalWeightsBehaveLikeRoundRobin() {
        assertThat(sequence(List.of(instance('A', 1), instance('B', 1), instance('C', 1)), 6)).isEqualTo("ABCABC");
    }

    @Test
    void adaptsWhenAnInstanceDisappearsAndComesBack() {
        List<BackendInstance> ab = List.of(instance('A', 3), instance('B', 1));
        sequence(ab, 3);
        List<BackendInstance> onlyB = List.of(instance('B', 1)); // A marked DOWN
        assertThat(sequence(onlyB, 3)).isEqualTo("BBB");
        // A returns with fresh state; distribution per 4 picks is again 3:1.
        String next = sequence(ab, 8);
        assertThat(next.chars().filter(c -> c == 'A').count()).isEqualTo(6);
    }
}
