package com.smartapi.demo;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "demo.service-name", havingValue = "restaurant-service")
public class RestaurantsController {

    private static final List<Map<String, Object>> RESTAURANTS = List.of(
            Map.of("id", 1, "name", "Udupi Grand", "cuisine", "South Indian", "rating", 4.5),
            Map.of("id", 2, "name", "Punjab Dhaba", "cuisine", "North Indian", "rating", 4.2));

    private final InstanceInfo instance;

    public RestaurantsController(InstanceInfo instance) {
        this.instance = instance;
    }

    /**
     * Restaurant data changes rarely, so responses say they may be cached by shared caches for 30 s.
     * The gateway's response cache honours this; orders (which change) send no such header.
     */
    private static final CacheControl CACHEABLE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    @GetMapping("/restaurants")
    public ResponseEntity<DemoResponse> list(@RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return ResponseEntity.ok().cacheControl(CACHEABLE)
                .body(instance.respond("Request handled successfully", RESTAURANTS, requestId));
    }

    @GetMapping("/restaurants/{id}")
    public ResponseEntity<DemoResponse> get(@PathVariable int id,
                                            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return RESTAURANTS.stream()
                .filter(restaurant -> restaurant.get("id").equals(id))
                .findFirst()
                .map(r -> ResponseEntity.ok().cacheControl(CACHEABLE)
                        .body(instance.respond("Request handled successfully", r, requestId)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(instance.respond("Restaurant " + id + " not found", null, requestId)));
    }
}
