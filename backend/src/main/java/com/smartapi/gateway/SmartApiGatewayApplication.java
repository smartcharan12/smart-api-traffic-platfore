package com.smartapi.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. {@code @SpringBootApplication} = {@code @Configuration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan} of this package and its sub-packages, which is how every
 * {@code @RestController}, {@code @Service} and repository below becomes a Spring bean.
 */
@SpringBootApplication
public class SmartApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartApiGatewayApplication.class, args);
    }
}
