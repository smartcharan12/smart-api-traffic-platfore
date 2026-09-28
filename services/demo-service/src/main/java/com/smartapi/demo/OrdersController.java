package com.smartapi.demo;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Only registered when this process runs as orders-service. */
@RestController
@ConditionalOnProperty(name = "demo.service-name", havingValue = "orders-service")
public class OrdersController {

    private static final List<Map<String, Object>> ORDERS = List.of(
            Map.of("id", 1, "item", "Masala Dosa", "quantity", 2, "status", "DELIVERED"),
            Map.of("id", 2, "item", "Paneer Tikka", "quantity", 1, "status", "PREPARING"),
            Map.of("id", 3, "item", "Veg Biryani", "quantity", 3, "status", "OUT_FOR_DELIVERY"));

    private final InstanceInfo instance;

    public OrdersController(InstanceInfo instance) {
        this.instance = instance;
    }

    @GetMapping("/orders")
    public DemoResponse list(@RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return instance.respond("Request handled successfully", ORDERS, requestId);
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<DemoResponse> get(@PathVariable int id,
                                            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return ORDERS.stream()
                .filter(order -> order.get("id").equals(id))
                .findFirst()
                .map(order -> ResponseEntity.ok(instance.respond("Request handled successfully", order, requestId)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(instance.respond("Order " + id + " not found", null, requestId)));
    }
}
