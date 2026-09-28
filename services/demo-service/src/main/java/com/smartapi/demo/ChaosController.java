package com.smartapi.demo;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Failure injection for demos and tests. Call it directly on the instance (not through the gateway):
 * <pre>
 *   curl -X POST 'localhost:9002/_chaos?mode=ERROR'              # every request returns 500
 *   curl -X POST 'localhost:9002/_chaos?mode=UNAVAILABLE&includeHealth=true'  # 503, /health too
 *   curl -X POST 'localhost:9002/_chaos?mode=SLOW&delayMs=3000'  # every request waits 3 s
 *   curl -X POST 'localhost:9002/_chaos?mode=NONE'               # back to normal
 * </pre>
 * Requests that came through a proxy (they carry X-Forwarded-For, which the gateway always sets) are
 * refused, so a client cannot break the backends via {@code POST /gateway/orders/_chaos}. A real
 * service would not have this endpoint at all.
 */
@RestController
public class ChaosController {

    private final ChaosState chaos;
    private final InstanceInfo instance;

    public ChaosController(ChaosState chaos, InstanceInfo instance) {
        this.chaos = chaos;
        this.instance = instance;
    }

    @PostMapping("/_chaos")
    public Map<String, Object> set(@RequestParam ChaosState.Mode mode, @RequestParam(defaultValue = "0") long delayMs,
                                   @RequestParam(defaultValue = "false") boolean includeHealth,
                                   @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        if (forwardedFor != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chaos controls must be called directly");
        }
        chaos.set(mode, delayMs, includeHealth);
        return get();
    }

    @GetMapping("/_chaos")
    public Map<String, Object> get() {
        var s = chaos.snapshot();
        return Map.of("instance", instance.instanceId(), "mode", s.mode(), "delayMs", s.delayMs(),
                "includeHealth", s.includeHealth());
    }
}
