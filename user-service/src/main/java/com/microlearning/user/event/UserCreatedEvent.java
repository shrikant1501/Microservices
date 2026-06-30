package com.microlearning.user.event;

/**
 * UserCreatedEvent — Domain Event published after a user is saved.
 *
 * DESIGN RULES FOR EVENTS:
 * 1. Past tense — "something HAPPENED"
 * 2. Carry enough data — consumers should NOT need to call back to the producer
 *    (that re-introduces coupling). Include email & name so notification-service
 *    can send the welcome email without calling user-service again.
 * 3. Immutable — use record (Java 16+)
 * 4. Serializable — Jackson will serialize this to JSON for Kafka
 *
 * OUTBOX NOTE:
 * In production, you'd save this payload into an outbox table in the SAME
 * transaction as the User entity, then a separate thread publishes it.
 * For learning, we publish directly from the service method.
 */
public record UserCreatedEvent(Long userId, String email, String name) {}
