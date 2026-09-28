package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.StubBackend;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.rate-limit.enabled=false",
        "gateway.cache.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1"})
class SimulationIntegrationTest extends GatewayTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    private StubBackend backend;

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
        serviceDiscovery.invalidateAll();
        backend = StubBackend.start("B");
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void adminCanInjectAFailureIntoAnInstance() throws Exception {
        long id = registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);

        HttpResponse<String> response = send("POST", "/api/simulations",
                "{\"instanceId\":%d,\"scenario\":\"UNAVAILABLE\"}".formatted(id));

        assertThat(response.statusCode()).isEqualTo(200);
        // The stub echoes what it received: the gateway called /_chaos directly with the right parameters.
        assertThat(response.body()).contains("\"backendStatus\":200")
                .contains("path\\\":\\\"/_chaos")
                .contains("mode=UNAVAILABLE&delayMs=0&includeHealth=true");
        assertThat(backend.hits()).isEqualTo(1);
    }

    @Test
    void regularUsersCannotInjectFailures() throws Exception {
        long id = registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);
        send("POST", "/api/auth/register", "{\"username\":\"viewer\",\"email\":\"v@example.com\",\"password\":\"correct-horse-battery\"}");
        String login = send("POST", "/api/auth/login", "{\"username\":\"viewer\",\"password\":\"correct-horse-battery\"}").body();
        String token = login.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        HttpResponse<String> response = send("POST", "/api/simulations",
                "{\"instanceId\":%d,\"scenario\":\"ERROR\"}".formatted(id), "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(backend.hits()).isZero();
    }

    @Test
    void unknownInstanceOrScenarioIsRejected() throws Exception {
        assertThat(send("POST", "/api/simulations", "{\"instanceId\":999,\"scenario\":\"ERROR\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/simulations", "{\"instanceId\":1,\"scenario\":\"EXPLODE\"}").statusCode()).isEqualTo(400);
    }
}
