package com.microlearning.notification.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NotificationConsumer — Phase 9: Idempotent consumer.
 *
 * ═══════════════════════════════════════════════════════════════
 * IDEMPOTENCY IMPLEMENTATION
 * ═══════════════════════════════════════════════════════════════
 *
 * Kafka delivers at-least-once. The same event can arrive multiple times:
 *   - Consumer crashed after processing but before committing offset
 *   - Kafka partition rebalancing
 *   - Manual replay from DLT
 *
 * WITHOUT idempotency: "welcome email sent 3 times to the same user"
 *
 * WITH idempotency:
 *   1. Every event carries a unique eventId (UUID from producer)
 *   2. Before processing, check if eventId was already processed
 *   3. If yes → skip (already done)
 *   4. If no  → process + record eventId
 *
 * PRODUCTION NOTE:
 * Replace the in-memory LRU cache with a database table:
 *   CREATE TABLE processed_events (
 *     event_id VARCHAR(36) PRIMARY KEY,
 *     processed_at TIMESTAMP
 *   );
 * This survives service restarts. The in-memory cache does NOT.
 * For learning purposes, the LRU cache demonstrates the pattern correctly.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    // In-memory idempotency store (LRU cache, max 1000 entries)
    // Production: replace with a DB table + TTL cleanup job
    private final Map<String, Boolean> processedEvents = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 1000;
                }
            }
    );

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000, multiplier = 2),
            autoCreateTopics = "false"
    )
    @KafkaListener(topics = "user-created", groupId = "notification-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void onUserCreated(UserCreatedEvent event) {
        // ─── IDEMPOTENCY CHECK ─────────────────────────────────────────────
        // In production: replace eventId source with a real field from the event
        // For now we use userId as a proxy idempotency key
        String idempotencyKey = "user-created:" + event.getUserId();

        if (processedEvents.containsKey(idempotencyKey)) {
            log.warn("[notification-service] DUPLICATE UserCreated event — skipping. key={}", idempotencyKey);
            return;
        }
        // ───────────────────────────────────────────────────────────────────

        log.info("[notification-service] Processing UserCreatedEvent: userId={}, email={}",
                event.getUserId(), event.getEmail());

        // Simulate sending welcome email
        log.info("[notification-service] ✉ Welcome email sent to {} <{}>",
                event.getName(), event.getEmail());

        // Record as processed AFTER successful processing
        // If processing fails, we do NOT record — so it will be retried
        processedEvents.put(idempotencyKey, Boolean.TRUE);
    }
}
