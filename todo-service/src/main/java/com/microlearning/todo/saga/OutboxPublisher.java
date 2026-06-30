package com.microlearning.todo.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microlearning.todo.domain.OutboxEvent;
import com.microlearning.todo.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * OutboxPublisher — polls the outbox table and publishes unpublished events to Kafka.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHY THIS EXISTS (Interview explanation)
 * ═══════════════════════════════════════════════════════════════
 *
 * The OutboxEvent is saved IN THE SAME TRANSACTION as the business record.
 * This publisher runs separately (every 5 seconds) and:
 *   1. Reads all unpublished outbox records
 *   2. Publishes each to Kafka
 *   3. Marks them as published
 *
 * GUARANTEES:
 *   - If this publisher crashes after publishing but before marking published
 *     → event is published again on restart (at-least-once delivery)
 *   - Consumer must be idempotent to handle duplicates
 *
 * PRODUCTION IMPROVEMENTS:
 *   - Use Debezium CDC (Change Data Capture) instead of polling
 *     → reads directly from DB transaction log (zero polling overhead)
 *   - Add a "claimed" state to prevent concurrent publisher instances
 *     from publishing the same event
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outboxRepo,
                           KafkaTemplate<String, String> kafka,
                           ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5000)  // poll every 5 seconds
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo.findByPublishedFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) return;

        log.debug("[outbox-publisher] Found {} unpublished events", pending.size());
        for (OutboxEvent event : pending) {
            try {
                kafka.send(event.getEventType(), event.getId(), event.getPayload());
                event.markPublished();
                outboxRepo.save(event);
                log.info("[outbox-publisher] Published event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("[outbox-publisher] Failed to publish event id={}: {}", event.getId(), e.getMessage());
                // Leave unpublished — will retry on next poll
            }
        }
    }
}
