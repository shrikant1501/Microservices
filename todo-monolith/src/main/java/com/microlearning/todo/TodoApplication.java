package com.microlearning.todo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TodoApplication — Phase 1 & 2: Modular Monolith
 *
 * ═══════════════════════════════════════════════════════════════
 * DOMAIN-DRIVEN DESIGN CLASSIFICATION (Phase 2)
 * ═══════════════════════════════════════════════════════════════
 *
 * This application contains THREE Bounded Contexts:
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │ BOUNDED CONTEXT 1: User Management (user/ package)      │
 *  │ Subdomain Type : Supporting                             │
 *  │ Aggregate Root : User                                   │
 *  │ Future Service : user-service (:8081)                   │
 *  └─────────────────────────────────────────────────────────┘
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │ BOUNDED CONTEXT 2: Task Management (todo/ package)      │
 *  │ Subdomain Type : Core (primary value of this app)       │
 *  │ Aggregate Root : Todo                                   │
 *  │ Future Service : todo-service (:8082)                   │
 *  └─────────────────────────────────────────────────────────┘
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │ BOUNDED CONTEXT 3: Communications (notification/ pkg)   │
 *  │ Subdomain Type : Generic (could be replaced by SaaS)    │
 *  │ Aggregate Root : Notification                           │
 *  │ Future Service : notification-service (:8083)           │
 *  └─────────────────────────────────────────────────────────┘
 *
 * PACKAGE STRUCTURE:
 * Organized by BUSINESS DOMAIN (bounded context), not by
 * technical layer. This is the "Modular Monolith" pattern and
 * makes future extraction into microservices significantly easier.
 *
 * EVOLUTION PLAN:
 * Phase 3 → Extract into 3 separate Spring Boot applications
 * Phase 4 → Add Kafka async messaging
 * Phase 5 → Add Eureka Service Discovery
 * Phase 6 → Add Spring Cloud API Gateway
 * Phase 7 → Add Spring Cloud Config Server
 */
@SpringBootApplication
public class TodoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TodoApplication.class, args);
    }
}
