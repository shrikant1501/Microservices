package com.microlearning.todo.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OutboxEvent — Phase 9: Transactional Outbox Pattern.
 *
 * ═══════════════════════════════════════════════════════════════
 * THE DUAL-WRITE PROBLEM THIS SOLVES
 * ═══════════════════════════════════════════════════════════════
 *
 * WITHOUT Outbox:
 *   todoRepo.save(todo);              // DB commit ✅
 *   kafkaTemplate.send("todo-created"); // Service crashes ❌
 *   → Event never published. Notification-service never notified.
 *
 * WITH Outbox:
 *   In ONE @Transactional:
 *     todoRepo.save(todo);            // Business record
 *     outboxRepo.save(outboxEvent);   // Event payload
 *   → Both commit atomically OR both rollback.
 *
 *   Separate OutboxPublisher polls this table:
 *     reads unpublished events
 *     sends to Kafka
 *     marks as published
 *   → Event is ALWAYS eventually published (at-least-once)
 *
 * This is the production-grade answer to:
 * "How do you guarantee an event is published after a DB save?"
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private String id;

    @Column(nullable = false)
    private String eventType;     // e.g. "todo-completed"

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;       // JSON serialized event

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean published = false;

    private LocalDateTime publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String eventType, String payload) {
        this.id = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
    }

    public String getId()              { return id; }
    public String getEventType()       { return eventType; }
    public String getPayload()         { return payload; }
    public LocalDateTime getCreatedAt(){ return createdAt; }
    public boolean isPublished()       { return published; }

    public void markPublished() {
        this.published = true;
        this.publishedAt = LocalDateTime.now();
    }
}
