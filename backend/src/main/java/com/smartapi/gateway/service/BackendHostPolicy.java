package com.smartapi.gateway.service;

import com.smartapi.gateway.exception.InvalidRequestException;
import java.util.Locale;
import java.util.Set;

/**
 * Hosts the gateway must never be pointed at (Server-Side Request Forgery). The gateway sends traffic
 * and health probes to whatever host is registered, so registering {@code 169.254.169.254} (the cloud
 * metadata service) would let an attacker make the gateway fetch cloud credentials. The primary
 * control is that only ADMINs can register instances; this blocklist is defence in depth.
 *
 * <p>Limitation: a hostname that RESOLVES to a blocked address is not caught here (checking at
 * registration time is also defeated by DNS rebinding). A production gateway would enforce this at
 * connection time, or with network egress rules.
 */
public final class BackendHostPolicy {

    private static final Set<String> BLOCKED_NAMES = Set.of("metadata.google.internal", "metadata", "0.0.0.0");

    private BackendHostPolicy() {
    }

    public static void check(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_NAMES.contains(h) || h.startsWith("169.254.") || h.startsWith("0.")) {
            throw new InvalidRequestException("host '%s' is not allowed as a backend (link-local/metadata address)"
                    .formatted(host));
        }
    }
}
