package com.microlearning.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * UserServiceApplication — Phase 3
 *
 * BOUNDED CONTEXT : User Management
 * SUBDOMAIN TYPE  : Supporting
 * AGGREGATE ROOT  : User
 * OWNS            : users table (H2, port 8081)
 * PUBLISHES       : UserCreated event (Phase 4 — Kafka)
 * CONSUMED BY     : todo-service (REST), notification-service (Kafka Phase 4)
 *
 * This service has NO knowledge of todos or notifications.
 * It only knows about Users.
 */
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
