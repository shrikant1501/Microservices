# Phase 15 — Advanced Messaging
## (Dead Letter Queue · Message Ordering · Consumer Groups · Exactly Once vs At Least Once · Retry Strategies)

> **80/20 Focus:** In every messaging interview, they will ask about delivery guarantees and what happens when a consumer fails. Know DLQ, at-least-once vs exactly-once, and idempotency cold.

---

## 1. The Problem

Kafka looks simple: producer sends → consumer receives. But real systems have edge cases:

```
What if the consumer crashes mid-processing?
What if the message is malformed JSON that crashes the consumer?
What if the downstream DB is temporarily unavailable?
What if the consumer processes the same message twice (at-least-once delivery)?
What if two consumers in the same group both process the same event?
What if message order matters but Kafka doesn't guarantee cross-partition order?
```

Each of these questions maps to a specific pattern. Phase 15 covers all of them.

---

## 2. Dead Letter Queue (DLQ)

### What is a DLQ?

A **Dead Letter Queue** is a separate Kafka topic (or queue in RabbitMQ) where messages that failed processing are sent after all retry attempts are exhausted.

```
Normal flow:
  Producer → [user-events] → Consumer → process ✅ → commit offset

With DLQ:
  Producer → [user-events] → Consumer → process ❌ (retry 3x)
                                       → after 3 failures
                                       → [user-events.DLT] ← Dead Letter Topic
                                       → commit original offset (don't block the queue)

Later:
  An engineer inspects [user-events.DLT]
  Fixes the bug / bad data
  Republishes messages back to [user-events]
```

### Why DLQ is critical

Without DLQ, a single poison message (malformed JSON, data that triggers a bug) blocks the entire consumer group **forever**. No other messages in that partition can be processed until the bad one succeeds. The DLQ breaks the deadlock.

### Spring Kafka — @RetryableTopic (DLQ built-in)

```java
// We already use this in notification-service (Phase 4)
@RetryableTopic(
    attempts = "4",                     // 1 original + 3 retries
    backoff = @Backoff(delay = 1000, multiplier = 2.0),  // 1s, 2s, 4s
    dltStrategy = DltStrategy.FAIL_ON_ERROR   // send to DLT after all attempts
)
@KafkaListener(topics = "user-events", groupId = "notification-group")
public void consume(String message) {
    // If this throws, Spring Kafka retries with backoff
    // After 4 total attempts → message goes to: user-events-dlt
}

// Handle the dead letter topic
@DltHandler
public void handleDlt(String message, @Header KafkaHeaders.RECEIVED_TOPIC String topic) {
    log.error("Message failed all retries. Topic={}, Message={}", topic, message);
    // Options:
    // 1. Alert on-call engineer (PagerDuty/Slack)
    // 2. Store in a "failed_events" DB table for manual review
    // 3. Publish to a monitoring system
}
```

### Topics created by @RetryableTopic

```
user-events                                 ← original topic
user-events-retry-0-notification-group      ← 1st retry (after 1s)
user-events-retry-1-notification-group      ← 2nd retry (after 2s)
user-events-retry-2-notification-group      ← 3rd retry (after 4s)
user-events-dlt                             ← dead letter topic (final resting place)
```

### DLQ monitoring — production must-have

```
Alert when DLT has unprocessed messages:
  Kafka consumer lag on user-events-dlt > 0 → PagerDuty alert
  
Dashboard:
  DLT message count per hour
  DLT message age (how long has oldest message been sitting?)
  Error type breakdown (which exceptions are causing failures?)
```

---

## 3. Message Ordering

### Kafka's ordering guarantee

```
WITHIN a partition:  order is GUARANTEED
ACROSS partitions:   order is NOT guaranteed

Partition 0: [event1, event3, event5]  ← consumed in order 1→3→5
Partition 1: [event2, event4, event6]  ← consumed in order 2→4→6

But consumer sees: 1,2,3,4,5,6 OR 1,3,2,5,4,6 OR any interleaving
```

### When ordering matters

```
✅ Ordering matters:
  User events: CREATE → UPDATE → DELETE
  If consumer processes DELETE before CREATE → error
  Order-service: ORDER_PLACED → PAYMENT_CONFIRMED → ORDER_SHIPPED
  
❌ Ordering doesn't matter:
  Email notifications — send in any order
  Analytics events — aggregate regardless of order
  Log events — timestamp is the order
```

### Guaranteeing order — partition key

The solution: **send related messages to the same partition using a consistent key.**

```java
// Kafka guarantees: all messages with the same key go to the same partition
// Same partition = same consumer = ordered processing

kafkaTemplate.send("user-events",
    userId.toString(),    // KEY — determines partition
    eventJson             // VALUE
);

// userId=42 → always Partition 2 (hash("42") % numPartitions)
// All events for user 42: CREATE→UPDATE→DELETE always in Partition 2
// Consumer of Partition 2 always sees them in order
```

### Ordering with multiple consumers

```
Topic: user-events, 3 partitions, 3 consumers (in same group)

Consumer 1 → Partition 0 → all userId % 3 == 0 events (in order)
Consumer 2 → Partition 1 → all userId % 3 == 1 events (in order)
Consumer 3 → Partition 2 → all userId % 3 == 2 events (in order)

✅ Each user's events are ordered
✅ Processing is parallel (3 consumers)
❌ Cannot have 4 consumers for 3 partitions (one consumer sits idle)
```

### When ordering is lost

```
1. No partition key → round-robin → related events on different partitions
2. Retries with backoff → event2 succeeds, event1 retry comes later → out of order
3. DLQ → event in DLT gets reprocessed after event3 already processed
4. Consumer group rebalance → partition reassigned mid-stream

PRODUCTION SOLUTION for ordering + retries:
  Sequence number in event payload.
  Consumer checks: is this the NEXT expected sequence for this userId?
  If not: store in a "pending" buffer until gap is filled.
  This is the "event sequencer" pattern.
```

---

## 4. Consumer Groups

### What a consumer group is

A consumer group is a set of consumers that collectively consume a topic. Kafka ensures each partition is assigned to exactly one consumer in the group.

```
Topic: user-events (6 partitions)

Group A (notification-service) — 3 consumers:
  Consumer A1 → Partitions 0, 1
  Consumer A2 → Partitions 2, 3
  Consumer A3 → Partitions 4, 5
  Result: each message processed ONCE by notification-service

Group B (audit-service) — 2 consumers:
  Consumer B1 → Partitions 0, 1, 2
  Consumer B2 → Partitions 3, 4, 5
  Result: each message processed ONCE by audit-service

BOTH groups process EVERY message independently.
This is how Kafka enables fan-out: publish once, consume N times.
```

### Consumer group rebalancing

When a consumer joins or leaves, Kafka **rebalances** partition assignments.

```
Before: 3 consumers, 6 partitions (2 each)
Consumer 2 crashes:

Rebalance triggered:
  Consumer 1 → Partitions 0, 1, 2, 3
  Consumer 3 → Partitions 4, 5
  Consumer 2 → (gone)

During rebalance: ALL consumers STOP processing (stop-the-world)
After rebalance: processing resumes from last committed offset
```

**Rebalance problems:**
- **Rebalance storm:** frequent joins/leaves → constant rebalancing → throughput drops
- **Long processing time:** consumer takes 5 minutes per message → heartbeat times out → Kafka thinks it's dead → rebalance triggered mid-processing

**Solutions:**
```properties
# Increase heartbeat interval to prevent premature rebalance detection
spring.kafka.consumer.session.timeout.ms=30000   # 30s (default 10s)
spring.kafka.consumer.heartbeat.interval.ms=3000  # 3s

# For long-processing consumers: pause the partition, process, resume
// In code:
consumer.pause(partitions);
process(record);  // takes long time
consumer.resume(partitions);
consumer.commitSync();
```

### Max consumers = num partitions

```
RULE: You can never have more ACTIVE consumers than partitions.
Extra consumers sit idle — they're standby in case another consumer fails.

If you need more parallelism: increase partition count.
But: you CANNOT decrease partition count without recreating the topic.
Plan partition count upfront (rule of thumb: 10-20x expected peak consumers).
```

---

## 5. Delivery Guarantees

### Three levels

```
AT MOST ONCE
─────────────────────────────────────────────────────────────
  Commit offset BEFORE processing.
  If consumer crashes after commit but before processing:
    → Message is lost forever.
  
  Use case: metrics, analytics (losing a few events is acceptable)
  Never use for: financial transactions, order processing

AT LEAST ONCE (default in Spring Kafka)
─────────────────────────────────────────────────────────────
  Commit offset AFTER successful processing.
  If consumer crashes after processing but before commit:
    → On restart, message is re-consumed.
    → Message is processed TWICE.
  
  Requirement: your consumer must be IDEMPOTENT.
  Use case: most business events (with idempotency)

EXACTLY ONCE
─────────────────────────────────────────────────────────────
  Message is processed exactly one time, guaranteed.
  Implemented via Kafka Transactions (atomic read-process-write).
  
  Kafka producer: enable.idempotence=true + transactional.id
  Kafka consumer: isolation.level=read_committed
  
  Cost: ~30% throughput reduction, increased complexity
  Use case: financial transactions, inventory deduction
```

### Exactly-Once with Spring Kafka

```java
// Producer side
@Bean
public ProducerFactory<String, String> producerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "tx-user-service-1");
    return new DefaultKafkaProducerFactory<>(config);
}

// Consumer side
@Bean
public ConsumerFactory<String, String> consumerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // skip uncommitted
    return new DefaultKafkaConsumerFactory<>(config);
}

// Transactional consume-process-produce
@Transactional("kafkaTransactionManager")
@KafkaListener(topics = "orders")
public void processOrder(String orderJson) {
    // All of these happen atomically:
    Order order = parse(orderJson);
    orderRepository.save(order);                        // DB write
    kafkaTemplate.send("payments", order.toPaymentEvent()); // produce
    // Offset commit happens inside the same Kafka transaction
    // Either ALL succeed or NONE (rollback)
}
```

### Idempotency — the practical exactly-once alternative

Exactly-once is complex and slow. Most production systems use **at-least-once + idempotency**:

```java
// NotificationConsumer — we implemented this in Phase 9
@KafkaListener(topics = "user-events")
public void consume(String message) {
    String eventId = parseEventId(message);

    // Check if already processed (idempotency store)
    if (processedEventIds.contains(eventId)) {
        log.info("Duplicate event {}, skipping", eventId);
        return;   // ← idempotent: safe to call twice
    }

    // Process
    sendWelcomeEmail(parseEmail(message));

    // Mark as processed
    processedEventIds.add(eventId);
}
```

For production: store processed event IDs in Redis (with TTL) or in a DB table with a unique constraint on `event_id`.

---

## 6. Retry Strategies

### The retry taxonomy

```
NO RETRY
  Fire and forget. Message fails → lost.
  Use for: non-critical events, metrics

IMMEDIATE RETRY
  Retry instantly on failure (1, 2, 3 times).
  Use for: transient network glitches (<50ms)
  Risk: hammers a struggling service faster

FIXED DELAY RETRY
  Retry after fixed interval: fail → wait 1s → retry → wait 1s → ...
  Better than immediate. Still predictable load on a struggling service.

EXPONENTIAL BACKOFF (best default)
  Retry after 1s, 2s, 4s, 8s, 16s...
  Gives the downstream service time to recover.
  With JITTER: add random ±20% to each delay.
  Jitter prevents retry storms (all consumers retrying at the same time).

RETRY STORM (anti-pattern):
  10,000 consumers all fail at 14:30:00
  All retry at 14:30:01
  All fail again, all retry at 14:30:02
  → Thundering herd — you just DDoS'd your own DB

JITTER FIX:
  Each consumer adds random delay (±500ms)
  Retries spread over 14:30:00–14:30:05
  Load is distributed → service recovers
```

### Spring Kafka retry strategies

```java
// Strategy 1: @RetryableTopic (non-blocking, recommended)
@RetryableTopic(
    attempts = "4",
    backoff = @Backoff(delay = 1000, multiplier = 2, random = true), // jitter
    exclude = {NonRetryableException.class}  // don't retry validation errors
)
@KafkaListener(topics = "user-events", groupId = "notification-group")
public void consume(String message) { ... }

// Strategy 2: ErrorHandlingDeserializer (deserialization failures)
// If JSON is malformed, the message can never be processed → send to DLT immediately
@Bean
public ConsumerFactory<String, String> consumerFactory() {
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
               ErrorHandlingDeserializer.class);
    config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
               StringDeserializer.class);
    // Deserialization failure → directly to DLT (no retries — retrying won't fix bad JSON)
}
```

### Retryable vs Non-retryable exceptions

```
RETRYABLE (transient, retry might succeed):
  - Connection timeout to downstream service
  - DB connection pool exhausted
  - Temporary network partition
  - HTTP 503 from a service starting up

NON-RETRYABLE (permanent, retry will always fail):
  - JSON deserialization error (bad message format)
  - Validation error (missing required field)
  - Business logic error (user already exists)
  - HTTP 400 Bad Request

In @RetryableTopic:
  exclude = {ValidationException.class, JsonParseException.class}
  → These go straight to DLT, no retries wasted
```

---

## 7. Complete Messaging Patterns Reference

```
Pattern              When to use                          Our impl
─────────────────────────────────────────────────────────────────────────────
Dead Letter Queue    Poison messages, failed after retries Phase 4 @RetryableTopic
At-Least-Once        Default, with idempotency             Phase 9 idempotency store
Exactly-Once         Financial, inventory                  Kafka Transactions
Ordered Messaging    State machines, sequential events     Partition key = entity ID
Fan-out              One event → N consumers               Multiple consumer groups
Retry w/ Backoff     Transient failures                    @Backoff(multiplier=2)
Poison Pill skip     Deserialization failure               ErrorHandlingDeserializer
Saga compensation    Distributed rollback via events       Phase 9 Outbox Pattern
```

---

## 8. Common Mistakes

| Mistake | Consequence | Fix |
|---|---|---|
| Committing offset before processing | Message loss on crash | Commit after successful processing |
| Retrying non-retryable errors | Wastes resources, fills retry topics | `exclude` known permanent failures |
| No DLQ | One bad message blocks partition forever | Always configure DLT |
| No jitter on retries | Retry storm DDoS'ing your own services | `random = true` in @Backoff |
| More consumers than partitions | Extra consumers idle | Pre-plan partition count |
| No eventId in message | Can't implement idempotency | Always include a UUID event ID |
| Using Kafka for request-response | High latency, complex correlation ID tracking | Use REST/gRPC for sync request-response |

---

## 9. Interview Questions

**Q1: What is a Dead Letter Queue and why is it necessary?**
> A DLQ (or Dead Letter Topic in Kafka) is where messages go after all retry attempts are exhausted. Without it, a single poison message — one that always causes an exception — blocks the entire partition indefinitely. No subsequent messages in that partition can be processed. The DLQ breaks this deadlock: the bad message is moved out of the main topic, offset is committed, and processing continues. The DLQ is then monitored — engineers investigate and either fix the consumer, fix the data, or discard the message.

**Q2: What is the difference between at-least-once and exactly-once delivery?**
> At-least-once: offset committed after processing. If the consumer crashes after processing but before committing, it reprocesses on restart — the message is processed at least once, possibly more. Requires idempotent consumers. Exactly-once: uses Kafka transactions to atomically commit the consumed offset AND the produced output — either both happen or neither does. Much more complex and ~30% slower. In practice, most teams use at-least-once with idempotency, which achieves the same observable outcome with less complexity.

**Q3: How does Kafka guarantee message ordering?**
> Kafka guarantees ordering WITHIN a partition. To ensure related messages (e.g., all events for a specific user) are ordered, use a consistent partition key — typically the entity ID (`userId.toString()`). All messages with the same key are routed to the same partition, consumed by the same consumer, in order. Cross-partition ordering is not guaranteed and requires additional mechanisms like sequence numbers.

**Q4: What is a consumer group rebalance and what causes it?**
> A rebalance is the process of redistributing partition assignments among consumers in a group. Triggers: a consumer joins, a consumer crashes, a consumer's session times out (heartbeat not sent within `session.timeout.ms`). During rebalance, all consumers in the group stop processing (stop-the-world). To minimize rebalances: increase `session.timeout.ms`, use `cooperative sticky` rebalance protocol (only moves partitions that need moving), avoid long processing that blocks heartbeats.

---

## 10. Tricky Interview Questions

**Q: You have exactly-once delivery configured, but your consumer saves to a DB and produces to another topic. The DB write succeeds but Kafka transaction rolls back. What state are you in?**
> This is the dual-write problem that exactly-once Kafka transactions do NOT solve. Kafka's exactly-once guarantees atomicity between reading an offset and writing to another Kafka topic. It does not include your external DB. If the DB write succeeds but the Kafka transaction rolls back, you have an inconsistent state — DB updated, Kafka offset not committed. The solution: use the Outbox Pattern (Phase 9). Write to DB + outbox table in one DB transaction. A separate OutboxPublisher reads the outbox and publishes to Kafka. The DB transaction is the source of truth.

**Q: Your notification-service uses @RetryableTopic with 4 attempts. A malformed JSON message hits the consumer. How many times does Kafka call your consumer before sending to DLT?**
> With default configuration, 4 times total (1 original + 3 retries). BUT if you've configured `ErrorHandlingDeserializer` correctly, deserialization failure goes DIRECTLY to the DLT — zero retries. A malformed JSON message will never succeed on retry, so retrying it wastes resources. The `exclude` parameter on `@RetryableTopic` or the `ErrorHandlingDeserializer` handles this by distinguishing deserialization failures (non-retryable) from processing failures (retryable).

---

## 11. Quick Revision Cheat Sheet

```
DEAD LETTER QUEUE
  → Where poison messages go after all retries exhausted
  → Breaks partition blocking (commit offset, move on)
  → @RetryableTopic creates retry + DLT topics automatically
  → Monitor: alert when DLT has unprocessed messages

ORDERING
  → Guaranteed WITHIN partition, not across
  → Consistent key (userId) → same partition → ordered
  → More partitions = more parallelism but same key always same partition

CONSUMER GROUPS
  → Each group processes EVERY message independently (fan-out)
  → Within group: each partition → exactly one consumer
  → Max active consumers = num partitions
  → Rebalance = stop-the-world redistribution (minimize with sticky protocol)

DELIVERY GUARANTEES
  At-most-once  → commit before process. Fast, lossy.
  At-least-once → commit after process. Duplicates possible. Need idempotency.
  Exactly-once  → Kafka transactions. 30% slower. DB not included.
  Practical:    → at-least-once + eventId idempotency check

RETRY STRATEGIES
  No retry          → fire and forget (metrics)
  Immediate retry   → transient glitch (risky)
  Exponential backoff → default choice (1s, 2s, 4s...)
  Jitter            → prevent retry storms (random ±20%)
  Non-retryable     → exclude={BadJsonException.class} → straight to DLT

PARTITION KEY RULES
  User events      → key = userId
  Order events     → key = orderId
  Payment events   → key = paymentId (NOT userId — ordering by payment, not user)
  Analytics events → no key needed (order irrelevant)
```
