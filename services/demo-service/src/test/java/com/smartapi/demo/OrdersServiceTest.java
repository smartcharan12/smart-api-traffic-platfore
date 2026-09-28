package com.smartapi.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"demo.service-name=orders-service", "server.port=9001"})
@AutoConfigureMockMvc
class OrdersServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChaosState chaos;

    @AfterEach
    void resetChaos() {
        chaos.set(ChaosState.Mode.NONE, 0);
    }

    @Test
    void responseIdentifiesServiceInstanceAndEchoesRequestId() throws Exception {
        mockMvc.perform(get("/orders/2").header("X-Request-ID", "abc-12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("orders-service"))
                .andExpect(jsonPath("$.instance").value("orders-9001"))
                .andExpect(jsonPath("$.requestId").value("abc-12345"))
                .andExpect(jsonPath("$.data.item").value("Paneer Tikka"));
    }

    @Test
    void ordersAreNotMarkedCacheable() throws Exception {
        mockMvc.perform(get("/orders")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().doesNotExist("Cache-Control"));
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        mockMvc.perform(get("/orders/99")).andExpect(status().isNotFound());
    }

    @Test
    void paymentsEndpointsAreNotExposedByOrdersService() throws Exception {
        mockMvc.perform(get("/payments")).andExpect(status().isNotFound());
    }

    @Test
    void chaosCannotBeTriggeredThroughTheGateway() throws Exception {
        mockMvc.perform(post("/_chaos").param("mode", "ERROR").header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders")).andExpect(status().isOk());
    }

    @Test
    void errorModeReturns500ButChaosAndHealthStillWork() throws Exception {
        mockMvc.perform(post("/_chaos").param("mode", "ERROR")).andExpect(status().isOk());

        mockMvc.perform(get("/orders")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Simulated failure"));
        mockMvc.perform(get("/health")).andExpect(status().isOk());

        mockMvc.perform(post("/_chaos").param("mode", "NONE")).andExpect(status().isOk());
        mockMvc.perform(get("/orders")).andExpect(status().isOk());
    }
}
