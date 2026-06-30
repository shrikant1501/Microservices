package com.microlearning.notification.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * NotificationConsumer — reacts to domain events from Kafka.
 *
 * ═══════════════════════════════════════════════════════════════
 * THIS IS THE KEY TRANSFORMATION FROM PHASE 1 MONOLITH
 * ═══════════════════════════════════════════════════════════════
 *
 * MONOLITH (Phase 1):
 *   UserService directly called notificationService.sendWelcomeEmail()
 *   → synchronous, coupled, notification failure = user registration failure
 *
 * MICROSERVICE (Phase 4):
 *   user-service publishes "UserCreated" event to Kafka
 *   notification-service INDEPENDENTLY consumes and processes it
 *   → user-service doesn't know notification-service exists
 *   → notification-service failure has ZERO impact on user creation
 *   → notification-service can be down, come back, and process all missed events
 *
 * @RetryableTopic — PRODUCTION ESSENTIAL:
 *   If processUserCreated() throws an exception:
 *   → Kafka retries the message up to 3 times with exponential backoff (2s, 4s, 8s)
 *   → After 3 failures, message goes to Dead Letter Topic: "user-created-dlt"
 *   → Operations team can inspect, fix the bug, and replay from DLT
 *   → No message is ever lost (at-least-once delivery)
 *
 * IDEMPOTENCY NOTE:
 *   With retries, the same message can be delivered multiple times.
 *   A production-ready consumer would check:
 *     if (notificationRepo.existsByUserIdAndType(userId, "WELCOME")) return;
 *   We skip this for clarity — covered conceptually in Phase 9 (Idempotency).
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    /**
     * @RetryableTopic: Spring Kafka 2.7+ feature.
     * Creates retry topics automatically: user-created-retry-0, user-created-retry-1, etc.
     * Far simpler than manual error handling. Remove if Kafka version doesn't support it.
     */
    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 2000, multiplier = 2),
        autoCreateTopics = "false"
    )
    @KafkaListener(topics = "user-created", groupId = "notification-group",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onUserCreated(UserCreatedEvent event) {
        log.info("[notification-service] Received UserCreatedEvent: userId={}, email={}",
                event.getUserId(), event.getEmail());

        // Simulate sending welcome email
        // Production: emailService.send(event.getEmail(), "Welcome " + event.getName() + "!")
        log.info("[notification-service] ✉ Welcome email sent to {} <{}>",
                event.getName(), event.getEmail());
    }
}
