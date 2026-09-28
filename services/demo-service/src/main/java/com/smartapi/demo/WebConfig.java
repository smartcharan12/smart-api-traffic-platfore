package com.smartapi.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ChaosInterceptor chaosInterceptor;

    public WebConfig(ChaosInterceptor chaosInterceptor) {
        this.chaosInterceptor = chaosInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /_chaos must always work (to switch chaos off). /health is affected only with includeHealth=true.
        registry.addInterceptor(chaosInterceptor).excludePathPatterns("/_chaos", "/error");
    }
}
