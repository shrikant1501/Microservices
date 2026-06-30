package com.microlearning.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NotificationServiceApplication — Phase 3 Stub
 *
 * BOUNDED CONTEXT : Communications
 * SUBDOMAIN TYPE  : Generic
 * STATUS          : Stub only — will be fully implemented in Phase 4
 *
 * WHY IS THIS HERE NOW?
 * Even as a stub, this demonstrates an important principle:
 * A service can be deployed and registered independently even before
 * it has full functionality. Other services (user-service, todo-service)
 * do not need to wait for notification-service to be "ready."
 *
 * In Phase 4, we will:
 * 1. Add Kafka consumer dependencies
 * 2. Consume UserCreated events → send welcome email
 * 3. Consume TodoCompleted events → send completion email
 * 4. Replace the direct NotificationService call from the monolith entirely
 */
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
