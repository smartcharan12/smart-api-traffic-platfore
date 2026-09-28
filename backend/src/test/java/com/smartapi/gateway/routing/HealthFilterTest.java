package com.smartapi.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.entity.InstanceStatus;
import com.smartapi.gateway.loadbalancer.BackendInstance;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthFilterTest {

    private static BackendInstance withStatus(long id, InstanceStatus status) {
        return new BackendInstance(id, 1, "localhost", 9000 + (int) id, 1, status);
    }

    @Test
    void onlyDownInstancesAreExcluded() {
        List<BackendInstance> all = List.of(withStatus(1, InstanceStatus.HEALTHY), withStatus(2, InstanceStatus.DOWN),
                withStatus(3, InstanceStatus.DEGRADED), withStatus(4, InstanceStatus.UNKNOWN));

        assertThat(new HealthFilter().routable(all)).extracting(BackendInstance::id).containsExactly(1L, 3L, 4L);
    }
}
