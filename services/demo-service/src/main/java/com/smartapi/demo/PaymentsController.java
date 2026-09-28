package com.smartapi.demo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stores payments in memory, per instance. That is intentional for the demo: if payments-service ran
 * as two instances behind the gateway, a payment created on one instance would be invisible on the
 * other. Real services keep state in a shared database so that any instance can serve any request.
 */
@RestController
@ConditionalOnProperty(name = "demo.service-name", havingValue = "payments-service")
public class PaymentsController {

    public record CreatePaymentRequest(Long orderId, Double amount) {
    }

    private final InstanceInfo instance;
    private final Map<Long, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public PaymentsController(InstanceInfo instance) {
        this.instance = instance;
    }

    @GetMapping("/payments")
    public DemoResponse list(@RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return instance.respond("Request handled successfully", List.copyOf(payments.values()), requestId);
    }

    @PostMapping("/payments")
    public ResponseEntity<DemoResponse> create(@RequestBody CreatePaymentRequest request,
                                               @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        if (request.orderId() == null || request.amount() == null || request.amount() <= 0) {
            return ResponseEntity.badRequest()
                    .body(instance.respond("orderId and a positive amount are required", null, requestId));
        }
        long id = ids.incrementAndGet();
        Map<String, Object> payment = Map.of("id", id, "orderId", request.orderId(), "amount", request.amount(),
                "status", "CAPTURED");
        payments.put(id, payment);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(instance.respond("Payment captured", payment, requestId));
    }
}
