package com.microlearning.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ApiGatewayApplication — Phase 6
 *
 * No special annotation needed — Spring Cloud Gateway auto-configures
 * everything from application.properties routes config.
 *
 * IMPORTANT: This is a WebFlux (reactive) application.
 *   - Do NOT add spring-boot-starter-web (servlet) — conflict.
 *   - All filters must return Mono<Void> (reactive pattern).
 *   - Even if you don't write reactive code, the framework is reactive.
 *
 * WHAT THIS PROVIDES:
 *   :8080/api/users/**  → routes to user-service  (Eureka-resolved)
 *   :8080/api/todos/**  → routes to todo-service   (Eureka-resolved)
 *   X-Correlation-Id injected on every request (via GlobalFilter)
 *   JWT validation added in Phase 10 (another GlobalFilter)
 */
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
