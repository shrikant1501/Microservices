# Phase 4 — Event-Driven Architecture & Kafka

> **80/20 Rule Applied:** Theory that interviewers actually ask + working code that proves you understand it.
> Skip: Kafka internals (partition rebalancing, Zookeeper vs KRaft deep-dives, consumer group coordinator protocol).
> Focus: Why EDA exists, core Kafka concepts, Producer/Consumer code, the patterns interviewers test you on.

---

## 1. The Problem (Why This Phase Exists)

After Phase 3, `user-service` still has this problem:

```java
// UserService.java (from monolith) — the coupling we never fixed
notificationService.sendWelcomeEmail(email, name);  // synchronous call
```

And `todo-service` has:
```java
userClient.getUserById(userId);  // blocks a thread, can cascade-fail
```

**Two categories of pain:**

| Pain | Root Cause | Solution |
|------|-----------|----------|
| Notification failure cancels user registration | Synchronous tight coupling | Decouple with events |
| user-service down → todo-service fails | Temporal coupling | Async messaging |
| Cascading failures | Synchronous call chain | Event-driven async |

**The fix:** Events. *"Something happened. Anyone who cares, react to it."*

---

## 2. Event-Driven Architecture — The Core Idea

```
BEFORE (synchronous):
  user-service → [HTTP] → notification-service
  Consequence: if notification is slow/down, user registration fails

AFTER (event-driven):
  user-service → publishes → [Kafka Topic: user-created] → consumed by → notification-service
  Consequence: user registration ALWAYS succeeds, notification happens eventually
```

**The 3 roles:**
- **Producer** — publishes an event (user-service after creating a user)
- **Broker** — stores and delivers events (Kafka)
- **Consumer** — reacts to events (notification-service)

**Key insight:** Producer does NOT know who consumes its events. It just says "UserCreated". Notification-service, analytics-service, fraud-detection-service — anyone can subscribe. **Zero code change in user-service** when you add a new consumer.

---

## 3. Kafka Fundamentals — What Interviewers Actually Ask

### Topic, Partition, Offset — The Holy Trinity

```
TOPIC: "user-created"  (logical channel — like a table)
  │
  ├─ Partition 0: [msg-0][msg-1][msg-4][msg-7] ← offset 0,1,4,7
  ├─ Partition 1: [msg-2][msg-5][msg-8]        ← offset 2,5,8
  └─ Partition 2: [msg-3][msg-6][msg-9]        ← offset 3,6,9
```

| Concept | What it is | Interview answer |
|---------|-----------|-----------------|
| **Topic** | Named feed of events | Like a database table for events |
| **Partition** | Ordered, immutable log within a topic | Unit of parallelism and ordering guarantee |
| **Offset** | Position of a message in a partition | Consumer tracks this to know what it has read |
| **Consumer Group** | Set of consumers sharing work | Each partition consumed by ONE consumer in the group |
| **Broker** | A Kafka server node | Stores partitions, serves producers/consumers |

### The Ordering Guarantee — Most Misunderstood Concept

> **Q: "Does Kafka guarantee message order?"**
>
> **A:** "Kafka guarantees ordering **within a partition**, not across partitions. If you need all events for user-id=7 to be processed in order, you set the **partition key** to `userId`. All events with the same key go to the same partition, guaranteeing order for that user. Global ordering across all users is not guaranteed and would be a bottleneck anyway."

### Consumer Group — The Scaling Mechanism

```
Topic "user-created" — 3 partitions

Consumer Group "notification-group" — 2 consumers:
  Consumer A → reads Partition 0 + Partition 1
  Consumer B → reads Partition 2

Consumer Group "analytics-group" — 3 consumers:
  Consumer C → reads Partition 0
  Consumer D → reads Partition 1
  Consumer E → reads Partition 2

KEY: Each consumer group gets ALL messages independently.
     notification-service and analytics-service both get every UserCreated event.
```

> **Interview Q: "How do you scale Kafka consumers?"**
> Add consumers to the consumer group (up to the number of partitions). If you have 3 partitions, max parallelism = 3 consumers. More consumers than partitions = idle consumers (waste). To scale beyond 3, increase the number of partitions first.

### At-Least-Once vs At-Most-Once vs Exactly-Once

| Delivery | Meaning | Risk | When to use |
|----------|---------|------|------------|
| **At-most-once** | May lose messages | Data loss | Metrics, logs — loss is acceptable |
| **At-least-once** | May deliver duplicates | Duplicate processing | Default, most use cases — pair with idempotency |
| **Exactly-once** | No loss, no duplicates | Higher latency/complexity | Financial transactions, critical data |

> **Interview Q: "How do you handle duplicate messages?"**
> "Make your consumer idempotent — processing the same message twice produces the same result as processing it once. For a 'send welcome email' consumer, check a `sent_notifications` table before sending. If already sent for this userId, skip it."

---

## 4. Architecture After Phase 4

```
BEFORE (Phase 3):
  user-service → notificationService.sendWelcomeEmail()  [synchronous, coupled]

AFTER (Phase 4):
  user-service → Kafka[user-created] → notification-service [async, decoupled]
  todo-service → Kafka[todo-completed] → notification-service [async, decoupled]

FULL PICTURE:

  user-service ──publishes──► [user-created topic]
                                       │
                              notification-service (consumes)
                              analytics-service    (consumes — future)

  todo-service ──publishes──► [todo-completed topic]
                                       │
                              notification-service (consumes)
```

---

## 5. Implementation

### What changes in user-service

Remove the direct `NotificationService` call → publish a `UserCreatedEvent` to Kafka.

### What changes in notification-service

Was a stub → now consumes `user-created` events and logs (simulates email).

### What we add

Two event classes, Kafka producer config in user-service, Kafka consumer config in notification-service.

---

## 6. Key Code — What Matters

### The Event Class (shared concept, not shared library)

```java
// user-service: UserCreatedEvent.java
public record UserCreatedEvent(Long userId, String email, String name) {}
```

The event is the **contract** between producer and consumer. It carries everything the consumer needs so it never has to call back to the producer.

### The Producer (user-service)

```java
kafkaTemplate.send("user-created", String.valueOf(userId), event);
//                  topic           partition key            payload
```

`userId` as key → all events for this user go to the same partition → ordered processing per user.

### The Consumer (notification-service)

```java
@KafkaListener(topics = "user-created", groupId = "notification-group")
public void onUserCreated(UserCreatedEvent event) {
    // send welcome email — if this crashes, Kafka retries delivery
    // if already processed (duplicate), check idempotency store and skip
}
```

### The Pattern Interviewers Love — Outbox Pattern

> **"How do you guarantee an event is published if the service crashes after DB save but before Kafka publish?"**

```java
// PROBLEM:
userRepository.save(user);    // succeeds
kafkaTemplate.send(event);    // service crashes HERE → event never published
                              // user exists in DB but no event was sent
                              // notification-service never sends welcome email

// SOLUTION: Outbox Pattern
// 1. Save user + outbox record IN THE SAME LOCAL TRANSACTION:
userRepository.save(user);
outboxRepository.save(new OutboxEvent("user-created", payload)); // same DB, same txn

// 2. Separate publisher polls outbox table and publishes to Kafka
// 3. Marks outbox record as published
// Result: atomicity guaranteed at DB level, eventual publish guaranteed by poller
```

This is the answer to: *"How do you ensure transactional consistency between your database and Kafka?"*

---

## 7. Kafka vs RabbitMQ — Interviewers Always Ask This

| | Kafka | RabbitMQ |
|---|-------|----------|
| **Model** | Log (pull-based, consumers control offset) | Queue (push-based, broker tracks delivery) |
| **Retention** | Messages kept for configurable time (days/weeks) | Messages deleted after consumption |
| **Replay** | Yes — rewind offset and replay | No — consumed messages are gone |
| **Throughput** | Millions of msgs/sec | Hundreds of thousands/sec |
| **Use when** | Event sourcing, audit logs, stream processing, replay needed | Task queues, RPC, routing, priority queues |
| **In Spring** | `spring-kafka` | `spring-amqp` |

> **Interview answer:** "I'd choose Kafka when I need high throughput, event replay, or multiple independent consumer groups. I'd choose RabbitMQ when I need flexible routing, priority queues, or simpler task distribution where replay isn't needed."

---

## 8. Implementation Code

### Dependencies to add to user-service pom.xml

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

### Dependencies to add to notification-service pom.xml

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

---

## 9. Top Interview Questions — Kafka / EDA

**Q1. What is an event vs a command vs a query?**
> - **Event:** "UserCreated" — something that happened. Past tense. The producer doesn't care what consumers do with it. Immutable fact.
> - **Command:** "CreateUser" — an instruction to do something. The sender expects it to be executed.
> - **Query:** "GetUser" — a request for data. Has a response.
> Events are the backbone of EDA. Commands and queries are synchronous REST/gRPC patterns.

**Q2. What is a dead letter queue (DLQ)?**
> When a consumer fails to process a message after N retries, Kafka (or the framework) routes it to a separate DLQ topic. This prevents a bad message from blocking the entire partition. An ops team can then inspect, fix, and replay DLQ messages. In Spring Kafka: `@RetryableTopic` + `@DltHandler`.

**Q3. How do you handle out-of-order events?**
> Use the partition key to ensure events for the same entity go to the same partition (ordering guaranteed per partition). For cross-entity ordering (rare), use event versioning — attach a sequence number or timestamp to each event and have consumers buffer/reorder if needed.

**Q4. What is event sourcing?**
> Storing the state of an entity as a sequence of events rather than the current state. Instead of `UPDATE users SET name='Bob'`, you append `UserNameChanged{userId, oldName, newName}`. The current state is derived by replaying all events. Kafka makes a natural event store. Trade-off: query complexity increases (you need a separate read model — which leads to CQRS).

**Q5. What is the difference between Event-Driven Architecture and Messaging?**
> Messaging is the mechanism (Kafka, RabbitMQ). EDA is the architectural style — designing services to communicate exclusively through events, making them temporally and logically decoupled. You can use messaging without EDA (e.g., a task queue). EDA implies that events are first-class citizens and services react to domain events rather than calling each other directly.

---

## 10. The 3 Tricky Questions Interviewers Use to Filter Seniors

**Tricky Q1: "In Kafka, if a consumer is down for 3 days and comes back up, what happens?"**
> Messages are retained in the topic for the configured retention period (default 7 days). When the consumer restarts, it resumes from its last committed offset. It will process all messages it missed — in order, per partition. This is the replay capability that makes Kafka fundamentally different from a traditional message queue. If retention expires before the consumer restarts, those messages are lost.

**Tricky Q2: "Two instances of notification-service are running. Will a UserCreated event be processed twice?"**
> No — if both instances belong to the **same consumer group**. Kafka assigns each partition to exactly one consumer within a group. So if there are 3 partitions and 2 instances, one instance handles 2 partitions and the other handles 1. The event is processed by exactly one instance. If they were in **different consumer groups**, each instance would get the message — which you'd want for independent analytics + notification services.

**Tricky Q3: "What happens if your Kafka consumer throws an exception while processing a message?"**
> By default, Spring Kafka retries the message (configurable) and then either skips it or sends it to a Dead Letter Topic. The critical risk: if you **commit the offset before processing** (auto-commit), a crash means the message is lost. If you **commit after processing** (manual ack or `ackMode = RECORD`), a crash means the message is redelivered (at-least-once). The correct production setup: disable auto-commit, use `AckMode.RECORD`, send failures to a DLT after N retries.

---

## 11. Quick Revision Cheat Sheet

```
EVENT-DRIVEN ARCHITECTURE
 └─ Producer publishes event, doesn't know/care who consumes
 └─ Broker stores & delivers (Kafka, RabbitMQ)
 └─ Consumer reacts independently

KAFKA CORE
 └─ Topic: named event feed
 └─ Partition: unit of ordering + parallelism
 └─ Offset: consumer position (committed to track progress)
 └─ Consumer Group: partitions shared among members (scale reads)
 └─ Ordering: guaranteed WITHIN a partition (use partition key!)

DELIVERY GUARANTEES
 └─ At-least-once: default, pair with idempotent consumer
 └─ Exactly-once: EOS (Idempotent Producer + Transactional API)

OUTBOX PATTERN (critical for production)
 └─ Save entity + outbox record in SAME local transaction
 └─ Separate poller publishes outbox records to Kafka
 └─ Guarantees: DB and Kafka are always consistent

KAFKA vs RABBIT
 └─ Kafka: log, retain, replay, high throughput, multiple consumer groups
 └─ RabbitMQ: queue, routing, priority, task distribution

DEAD LETTER TOPIC (DLT)
 └─ Failed messages after N retries → DLT
 └─ Prevents bad message blocking entire partition
 └─ @RetryableTopic + @DltHandler in Spring Kafka

SCALING
 └─ Parallelism = number of partitions
 └─ Consumers in same group ≤ number of partitions
```
