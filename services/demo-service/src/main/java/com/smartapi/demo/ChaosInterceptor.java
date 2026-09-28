package com.smartapi.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies the current chaos mode before a business endpoint runs. */
@Component
public class ChaosInterceptor implements HandlerInterceptor {

    private final ChaosState chaos;
    private final InstanceInfo instance;

    public ChaosInterceptor(ChaosState chaos, InstanceInfo instance) {
        this.chaos = chaos;
        this.instance = instance;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        var settings = chaos.snapshot();
        if ("/health".equals(request.getRequestURI()) && !settings.includeHealth()) {
            return true;
        }
        switch (settings.mode()) {
            case SLOW -> Thread.sleep(settings.delayMs());
            case ERROR, UNAVAILABLE -> {
                response.setStatus(settings.mode() == ChaosState.Mode.ERROR ? 500 : 503);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"service\":\"%s\",\"instance\":\"%s\",\"message\":\"Simulated failure\"}"
                        .formatted(instance.serviceName(), instance.instanceId()));
                return false; // stop: the controller is not called
            }
            case NONE -> {
            }
        }
        return true;
    }
}
