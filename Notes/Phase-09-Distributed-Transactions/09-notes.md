# Phase 9 — Distributed Transactions (Saga, CQRS, Idempotency)

> **The hardest concept in microservices. The question that separates senior from mid-level interviews.**
> 80/20: Master the Saga pattern (Choreography vs Orchestration), understand why 2PC fails, know CQRS at concept level, and nail Idempotency — these 4 topics cover 95% of interview questions on this subject.

---

## 1. The Problem — Why Distributed Transactions Are Hard

In the monolith, this was trivial:

```java
// MONOLITH — one @Transactional wraps everything
@Transactional
public void placeOrder(OrderRequest req) {
    Order order = orderRepo.save(new Order(req));      // Step 1
    inventoryService.deductStock(req.getProductId());  // Step 2
    paymentService.charge(req.getUserId(), req.getAmount()); // Step 3
    notificationService.sendConfirmation(req.getEmail()); // Step 4
}
// If Step 3 fails → entire transaction rolls back atomically (ACID)
// Database guarantees: either ALL steps succeed or NONE do
```

In microservices, these 4 steps live in 4 separate services with 4 separate databases:

```
Order Service    → saves order        → orders_db
Inventory Service → deducts stock     → inventory_db
Payment Service  → charges card       → payments_db
Notification     → sends email        → notifications_db

PROBLEM: There is NO distributed @Transactional.
         You CANNOT do ACID across 4 separate databases.

FAILURE SCENARIO:
  Step 1 ✅ order saved
  Step 2 ✅ stock deducted
  Step 3 ❌ payment FAILS (card declined)

  What happens to Step 1 and Step 2?
  The order is in the database. The stock is gone.
  But the payment never happened.
  SYSTEM IS NOW INCONSISTENT.
```

This is the core distributed transaction problem.

---

## 2. Why Two-Phase Commit (2PC) is NOT the Answer

The naive solution is **Two-Phase Commit** — a distributed protocol where a coordinator asks all participants "are you ready to commit?" and only commits when everyone agrees.

```
Phase 1 — Prepare:
  Coordinator → Order Service:    "Can you commit?"  → "Yes" (lock row)
  Coordinator → Inventory Service:"Can you commit?"  → "Yes" (lock row)
  Coordinator → Payment Service:  "Can you commit?"  → "Yes" (lock row)

Phase 2 — Commit:
  Coordinator → All: "Commit now"
```

**Why 2PC fails in microservices:**

| Problem | Impact |
|---------|--------|
| **Blocking** | All participants hold locks during the protocol. If coordinator crashes after Phase 1, locks are held forever. |
| **Single point of failure** | Coordinator goes down → entire system stalls |
| **Performance** | Synchronous, blocking across all services. Kills throughput. |
| **Tight coupling** | All services must be available simultaneously |
| **CAP theorem** | 2PC forces CP — sacrifices availability for consistency |

> **Interview answer:** "2PC is not used in microservices because it's blocking, requires all services to be simultaneously available, creates a coordinator SPOF, and scales poorly. Modern microservices use the Saga pattern instead — which achieves eventual consistency without distributed locks."

---

## 3. The Saga Pattern — The Real Solution

A **Saga** is a sequence of local transactions, each published as an event. If any step fails, compensating transactions undo the previous steps.

```
SAGA: Place Order

Step 1: Order Service      → creates order       → publishes OrderCreated
Step 2: Inventory Service  → deducts stock        → publishes StockDeducted
Step 3: Payment Service    → charges card         → publishes PaymentProcessed
Step 4: Notification       → sends email          → publishes EmailSent

FAILURE at Step 3 (payment fails):
  Payment Service publishes → PaymentFailed

COMPENSATING TRANSACTIONS (rollback):
  Step 2 compensate: Inventory Service → restores stock
  Step 1 compensate: Order Service     → cancels order
```

**Key insight:** No distributed lock. No 2PC. Each step is a local transaction. Compensation is explicit application logic (not database rollback).

---

## 4. Two Saga Implementations — The Critical Interview Question

### Choreography Saga

Services react to each other's events directly. No central coordinator.

```
Order Service
  publishes → [order-created]
                    │
                    ▼
          Inventory Service (consumes order-created)
          deducts stock
          publishes → [stock-deducted]
                           │
                           ▼
                 Payment Service (consumes stock-deducted)
                 charges card
                 publishes → [payment-processed] OR [payment-failed]
                                    │
                      ┌─────────────┴──────────────┐
                      ▼ (success)                  ▼ (failure)
               Notification                 Inventory Service
               sends email                 (consumes payment-failed)
                                           restores stock
                                                │
                                                ▼
                                          Order Service
                                          (consumes stock-restored)
                                          cancels order
```

**Pros:** Loose coupling, no SPOF, each service is autonomous
**Cons:** Hard to track overall saga state, debugging is complex, easy to create circular event chains

### Orchestration Saga

A central **Saga Orchestrator** tells each service what to do and handles failures.

```
                    ┌─────────────────────┐
                    │   Order Orchestrator │
                    │  (state machine)     │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
    "DeductStock"      "ChargePayment"    "SendEmail"
         │                   │                 │
    Inventory            Payment           Notification
    Service              Service           Service
         │                   │
     success/fail         success/fail
         └───────────────────┘
                  │
         Orchestrator decides
         next step or compensation
```

**Pros:** Clear saga state, easy to monitor, compensations are centralized
**Cons:** Orchestrator can become a god object, coupling to orchestrator service

### Which to Choose?

| | Choreography | Orchestration |
|---|---|---|
| **Best for** | Simple, linear flows (3-4 steps) | Complex flows with branching, retries, parallel steps |
| **Visibility** | Hard — events scattered across services | Easy — orchestrator is the source of truth |
| **Coupling** | Low — services don't know about each other | Medium — services know about orchestrator |
| **Testing** | Hard — need all services to test a flow | Easier — test orchestrator state machine |
| **Netflix, Uber use** | Choreography for simple, Orchestration for critical | Mixed |

> **Interview answer:** "I'd use choreography for simple linear flows where each service has one next step. For complex business processes with branching, parallel steps, timeouts, and multiple compensation paths, I'd use orchestration — the explicit state machine makes the saga trackable and debuggable."

---

## 5. Idempotency — The Unsung Hero

Every saga uses messaging (Kafka), and Kafka delivers messages **at-least-once**. This means your consumer **can receive the same message twice**.

```
SCENARIO:
  Payment Service processes "charge $100" message
  Charges the card ✅
  Tries to commit the offset to Kafka
  Service crashes ❌ before committing

  Kafka redelivers the message to the restarted instance
  Payment Service charges $100 AGAIN ← DISASTER
```

**Idempotency** = processing the same message twice produces the same result as once.

### Implementation Pattern

```java
@KafkaListener(topics = "order-created")
@Transactional
public void handleOrderCreated(OrderCreatedEvent event) {
    // IDEMPOTENCY CHECK: has this event already been processed?
    if (processedEventRepo.existsByEventId(event.getEventId())) {
        log.warn("Duplicate event detected, skipping. eventId={}", event.getEventId());
        return;  // idempotent — do nothing, return success
    }

    // Process the event
    paymentService.charge(event.getUserId(), event.getAmount());

    // Record that we processed this event
    processedEventRepo.save(new ProcessedEvent(event.getEventId(), LocalDateTime.now()));
}
```

**The idempotency key** is a unique identifier per event (UUID generated by the producer, stored in the event payload). Consumers store processed event IDs to detect duplicates.

> **Interview Q: "How do you make a Kafka consumer idempotent?"**
> "Include a unique event ID in every event payload (UUID). The consumer checks a `processed_events` table before processing. If the ID already exists, skip and return successfully. This table acts as an idempotency store. The check + processing + recording must happen in the same local transaction for correctness."

---

## 6. CQRS — What You Need to Know for Interviews

**Command Query Responsibility Segregation** — separate the write model (Command) from the read model (Query).

```
WRITE SIDE (Commands):                  READ SIDE (Queries):
  POST /orders              →             GET /orders/{id}
  OrderCommandService       →             OrderQueryService
  Normalised DB (writes)    →             Denormalised DB (reads)
  Strong consistency        →             Eventual consistency

  OrderPlaced event →→→→→→→→→→→→→→→→→→→ Order Projection updates read DB
```

**Why it matters for microservices:**
- Read and write workloads scale independently
- Read model can be optimised for queries (denormalised, cached)
- Pairs naturally with Event Sourcing
- Solves the "cross-service join" problem — each service maintains its own read model by consuming events from other services

**What interviewers actually ask:**
1. "What is CQRS?" → Separate write and read models
2. "Why would you use it?" → Different scaling needs; complex queries on normalised data; read/write ratio is imbalanced
3. "What is the downside?" → Eventual consistency between write and read models; more complexity; two models to maintain

---

## 7. The Outbox Pattern (Revisited — Critical for Sagas)

Every saga step publishes an event. What if the service saves to DB but crashes before publishing to Kafka?

```
// PROBLEM:
inventoryService.deductStock();     // DB commit ✅
kafkaTemplate.send("stock-deducted"); // CRASH ❌ — event never published
// Saga is stuck — next step never triggered
```

**Solution: Transactional Outbox**

```java
@Transactional
public void deductStock(OrderCreatedEvent event) {
    // 1. Business logic
    inventory.deductStock(event.getProductId(), event.getQuantity());

    // 2. Save event to outbox IN SAME LOCAL TRANSACTION
    outboxRepo.save(new OutboxEvent(
        UUID.randomUUID(),
        "stock-deducted",
        serialize(new StockDeductedEvent(event.getOrderId()))
    ));
    // Both writes are atomic — either both commit or both rollback
}

// Separate publisher thread/job reads outbox and publishes to Kafka
// Marks outbox records as published after successful send
```

```
GUARANTEE:
  DB commit includes outbox record → publisher will eventually send the event
  If publisher crashes → it restarts and re-reads unpublished outbox records
  Result: event is ALWAYS eventually published (at-least-once)
  Pair with idempotent consumer to handle duplicates
```

---

## 8. Key Interview Questions

**Q1. What is a distributed transaction and why is it difficult in microservices?**
> A distributed transaction is an operation that spans multiple services/databases and must succeed or fail atomically. It's hard in microservices because each service has its own database — there's no shared transaction coordinator. 2PC (two-phase commit) theoretically solves this but fails in practice: it's blocking, creates a coordinator SPOF, holds locks across services, and sacrifices availability. The microservices solution is the Saga pattern — eventual consistency through compensating transactions.

**Q2. Explain the Saga pattern.**
> A Saga breaks a distributed transaction into a sequence of local transactions. Each step publishes an event that triggers the next step. If any step fails, the saga executes compensating transactions to undo the previous steps. This achieves eventual consistency without distributed locks. There are two implementations: Choreography (services react to events directly — decoupled but hard to track) and Orchestration (a central orchestrator directs each step — easier to monitor but more coupling).

**Q3. What is the difference between Choreography and Orchestration Saga?**
> In Choreography, services publish events and react to each other's events directly — no central coordinator. It's loosely coupled but hard to debug because saga state is distributed across all services. In Orchestration, a dedicated Saga Orchestrator service directs each participant what to do and handles the compensation logic. It's easier to monitor (single source of truth for saga state) but the orchestrator can become complex. Use choreography for simple linear flows, orchestration for complex branching business processes.

**Q4. What is idempotency and why is it critical for sagas?**
> Idempotency means that executing an operation multiple times produces the same result as executing it once. It's critical for sagas because Kafka (and all message systems) provide at-least-once delivery — a consumer may process the same message multiple times (due to retries, rebalancing, crashes). Without idempotency, "charge $100" could execute twice. The solution: include a unique event ID in every message and use an idempotency store (database table of processed event IDs) to detect and skip duplicates.

**Q5. What is the Outbox Pattern?**
> The Outbox Pattern solves the dual-write problem: a service that saves to its database AND publishes an event can fail between the two operations. Solution: save both the business record and the event payload in the same local database transaction (in an `outbox` table). A separate publisher reads the outbox and publishes to Kafka. Since the DB write and outbox write are in the same transaction, they're atomic — the event is always eventually published even if the publisher crashes.

---

## 9. Tricky Interview Questions

**Q. Can a compensating transaction fail? What then?**
> Yes — and this is the hardest part of sagas. If a compensating transaction fails, the system is in an inconsistent state with no automatic recovery. Solutions: (1) Retry the compensation with exponential backoff (idempotent compensations are critical here). (2) Alert an operator — some compensations require human intervention (e.g., a refund that fails because the payment provider is down). (3) Design compensations to be idempotent so retrying is always safe. This is why sagas are harder than 2PC in some ways — you must design every compensation carefully.

**Q. What is the difference between a Saga and an eventual consistent system?**
> Eventual consistency is the broad property: the system will reach a consistent state given no new writes. A Saga is a specific pattern to achieve it: a defined sequence of steps with explicit compensation logic. Every saga achieves eventual consistency, but not every eventually consistent system uses sagas. For example, a Kafka consumer that updates a read model is eventually consistent but is not a saga — there are no compensating transactions.

---

## 10. Quick Revision Cheat Sheet

```
DISTRIBUTED TRANSACTION PROBLEM
 └─ No @Transactional across multiple databases
 └─ 2PC: blocking, SPOF, kills performance — don't use

SAGA PATTERN
 └─ Sequence of local transactions + compensating transactions
 └─ No distributed lock — eventual consistency

CHOREOGRAPHY
 └─ Services react to each other's events (no coordinator)
 └─ Pros: loose coupling    Cons: hard to track/debug

ORCHESTRATION
 └─ Central orchestrator directs each step
 └─ Pros: trackable state  Cons: orchestrator complexity

IDEMPOTENCY (critical — Kafka is at-least-once)
 └─ Include unique event ID in every message
 └─ Consumer checks processed_events table before acting
 └─ Check + process + record in ONE local transaction

OUTBOX PATTERN (dual-write safety)
 └─ Save business record + outbox event in SAME transaction
 └─ Separate publisher reads outbox → sends to Kafka
 └─ Guarantees event is always published (at-least-once)

CQRS
 └─ Separate write model (normalised, strongly consistent)
 └─ and read model (denormalised, eventually consistent)
 └─ Read model updated by consuming domain events
 └─ Pros: independent scaling, optimised queries
 └─ Cons: eventual consistency, two models to maintain

SAGA vs 2PC
 └─ 2PC: synchronous, blocking, SPOF, locks
 └─ Saga: async, non-blocking, eventual consistency, no locks

COMPENSATING TRANSACTION
 └─ Must be idempotent (safe to retry)
 └─ Must be designed for every step that modifies state
 └─ Failure in compensation = inconsistency, needs human intervention
```

---

## 11. Saga in Our Todo Application

Our current flow:

```
POST /api/todos (todo-service)
  Step 1: Validate user exists         (calls user-service)
  Step 2: Save todo                    (local DB)
  Step 3: Notify user                  (Kafka → notification-service)

FAILURE SCENARIOS:
  Step 1 fails → return error, nothing saved (no saga needed)
  Step 2 fails → transaction rolled back, no event published (safe)
  Step 3: notification-service fails
    → todo is saved (Step 2 committed)
    → notification may not send
    → compensation: retry via @RetryableTopic (DLT after 3 retries)
    → eventual guarantee: notification WILL be sent (or alerted to ops)
```

This is a simple choreography saga. The compensation for notification failure is retry + DLT — not a hard rollback of the todo itself. This is a deliberate product decision: *a failed notification does not invalidate a successfully created todo.*
