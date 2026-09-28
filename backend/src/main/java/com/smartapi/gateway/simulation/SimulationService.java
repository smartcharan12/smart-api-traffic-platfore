package com.smartapi.gateway.simulation;

import com.smartapi.gateway.dto.ServiceInstanceResponse;
import com.smartapi.gateway.exception.InvalidRequestException;
import com.smartapi.gateway.service.ServiceInstanceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Failure injection for demos: tells a DEMO backend instance (services/demo-service) to misbehave
 * by calling its {@code /_chaos} endpoint directly (not through the gateway, whose requests the demo
 * service refuses for chaos). Only ADMINs can call it (SecurityConfig), and it can be switched off
 * entirely with {@code gateway.simulation.enabled=false}, which is what a real deployment would do.
 *
 * <pre>
 *   ERROR        HTTP 500 on business endpoints            -> circuit OPEN (health probes still OK)
 *   SLOW         +1.5 s per request, health probes too     -> health DEGRADED
 *   TIMEOUT      +8 s per request (> gateway timeout 5 s)  -> 504s, retries, circuit OPEN
 *   UNAVAILABLE  HTTP 503 everywhere, health probes too    -> HEALTHY -> DEGRADED -> DOWN, circuit OPEN
 *   RECOVER      back to normal                            -> HALF_OPEN -> CLOSED, DOWN -> HEALTHY
 * </pre>
 */
@Service
public class SimulationService {

    public enum Scenario {
        ERROR("ERROR", 0, false),
        SLOW("SLOW", 1500, true),
        TIMEOUT("SLOW", 8000, false),
        UNAVAILABLE("UNAVAILABLE", 0, true),
        RECOVER("NONE", 0, false);

        final String chaosMode;
        final long delayMs;
        final boolean includeHealth;

        Scenario(String chaosMode, long delayMs, boolean includeHealth) {
            this.chaosMode = chaosMode;
            this.delayMs = delayMs;
            this.includeHealth = includeHealth;
        }
    }

    public record Result(long instanceId, String address, Scenario scenario, int backendStatus, String backendReply) {
    }

    private final ServiceInstanceService instances;
    private final boolean enabled;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SimulationService(ServiceInstanceService instances,
                             @Value("${gateway.simulation.enabled:true}") boolean enabled) {
        this.instances = instances;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Result apply(long instanceId, Scenario scenario) {
        if (!enabled) {
            throw new InvalidRequestException("Failure simulation is disabled (gateway.simulation.enabled=false)");
        }
        ServiceInstanceResponse instance = instances.findById(instanceId);
        String address = instance.host() + ":" + instance.port();
        URI uri = URI.create("http://" + address + "/_chaos?mode=" + scenario.chaosMode + "&delayMs="
                + scenario.delayMs + "&includeHealth=" + scenario.includeHealth);
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new InvalidRequestException("Instance %s has no /_chaos endpoint (not a demo service)"
                        .formatted(address));
            }
            return new Result(instanceId, address, scenario, response.statusCode(), response.body());
        } catch (java.io.IOException e) {
            throw new InvalidRequestException("Could not reach %s to apply the simulation: %s"
                    .formatted(address, e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
