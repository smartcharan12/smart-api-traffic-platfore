package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Full stack: HTTP -> controller -> service -> JPA -> real PostgreSQL (schema from Flyway). */
@SpringBootTest(properties = {"spring.data.redis.url=redis://127.0.0.1:1", "gateway.health-check.enabled=false"})
@AutoConfigureMockMvc
class RegistryApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
    }

    private long createService(String name, String basePath) throws Exception {
        String body = mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "basePath": "%s", "loadBalancingStrategy": "WEIGHTED_ROUND_ROBIN"}
                                """.formatted(name, basePath)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("createdAt").isNull()).isFalse();
        return json.get("id").asLong();
    }

    private void createInstance(long serviceId, int port, int weight) throws Exception {
        mockMvc.perform(post("/api/service-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId": %d, "host": "localhost", "port": %d, "weight": %d}
                                """.formatted(serviceId, port, weight)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void registerServiceWithInstancesAndListThem() throws Exception {
        long ordersId = createService("orders-service", "/orders");
        long paymentsId = createService("payments-service", "/payments");
        createInstance(ordersId, 9001, 3);
        createInstance(ordersId, 9002, 1);
        createInstance(paymentsId, 9101, 1);

        mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("orders-service"));

        mockMvc.perform(get("/api/service-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].serviceName").value("orders-service"));

        mockMvc.perform(get("/api/service-instances").param("serviceId", String.valueOf(ordersId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].weight").value(3));
    }

    @Test
    void duplicateServiceNameReturns409() throws Exception {
        createService("orders-service", "/orders");

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "orders-service", "basePath": "/orders-v2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate resource"));
    }

    @Test
    void duplicateInstanceEndpointReturns409() throws Exception {
        long ordersId = createService("orders-service", "/orders");
        createInstance(ordersId, 9001, 1);

        mockMvc.perform(post("/api/service-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId": %d, "host": "LOCALHOST", "port": 9001}
                                """.formatted(ordersId)))
                .andExpect(status().isConflict());
    }

    @Test
    void instanceForUnknownServiceReturns400() throws Exception {
        mockMvc.perform(post("/api/service-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId": 999, "host": "localhost", "port": 9001}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("serviceId 999 does not refer to a registered service"));
    }

    @Test
    void deletingServiceCascadesToInstances() throws Exception {
        long ordersId = createService("orders-service", "/orders");
        createInstance(ordersId, 9001, 1);
        createInstance(ordersId, 9002, 1);

        mockMvc.perform(delete("/api/services/" + ordersId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/services/" + ordersId)).andExpect(status().isNotFound());
        Integer remaining = jdbcTemplate.queryForObject("SELECT count(*) FROM service_instances", Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    void patchStatusChangesInstanceStatus() throws Exception {
        long ordersId = createService("orders-service", "/orders");
        createInstance(ordersId, 9001, 1);

        mockMvc.perform(patch("/api/service-instances/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"));
        mockMvc.perform(get("/api/service-instances/1")).andExpect(jsonPath("$.status").value("DOWN"));

        mockMvc.perform(patch("/api/service-instances/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BROKEN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/service-instances/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DOWN\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthReportsDatabaseUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                // No Redis in this test: DEGRADED (still 200), because Redis is optional.
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.database").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("DOWN"));
    }

    @Test
    void unknownUrlReturns404NotAnError500() throws Exception {
        mockMvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
    }
}
