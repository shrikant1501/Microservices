package com.microlearning.todo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TodoApplication — Phase 1: Monolithic Architecture
 *
 * ARCHITECTURE NOTE:
 * This is a deliberate monolith. All three business domains
 * (User, Todo, Notification) are packaged and deployed together.
 *
 * Observe how the modules are organized by BUSINESS DOMAIN
 * (user/, todo/, notification/) rather than by TECHNICAL LAYER
 * (controllers/, services/, repositories/).
 *
 * This domain-first package structure is called a "Modular Monolith"
 * and makes future extraction into microservices significantly easier.
 * Each package is a candidate for a future microservice.
 *
 * WHAT WE WILL DO LATER:
 * Phase 3  → Extract User Service
 * Phase 3  → Extract Todo Service
 * Phase 3  → Extract Notification Service
 * Phase 5  → Add Eureka Service Discovery
 * Phase 6  → Add Spring Cloud API Gateway
 * Phase 7  → Add Spring Cloud Config Server
 * ...and so on
 */
@SpringBootApplication
public class TodoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TodoApplication.class, args);
    }
}
