package com.smartapi.gateway.loadbalancer;

import com.smartapi.gateway.entity.InstanceStatus;

/** Test helper: instance "A" has id 1 and port 9001, "B" id 2 and port 9002, and so on. */
final class Instances {

    private Instances() {
    }

    static BackendInstance instance(char name, int weight) {
        int index = name - 'A' + 1;
        return new BackendInstance(index, 1L, "host-" + name, 9000 + index, weight, InstanceStatus.HEALTHY);
    }

    static String name(BackendInstance instance) {
        return String.valueOf((char) ('A' + instance.id() - 1));
    }
}
