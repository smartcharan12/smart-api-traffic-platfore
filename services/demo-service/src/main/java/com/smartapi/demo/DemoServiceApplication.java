package com.smartapi.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A deliberately small backend used only to demonstrate the gateway. The same jar runs as
 * orders-service, payments-service or restaurant-service depending on {@code demo.service-name}.
 */
@SpringBootApplication
public class DemoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}
