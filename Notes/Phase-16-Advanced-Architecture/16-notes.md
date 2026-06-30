# Phase 16 — Advanced Architecture
## (CQRS · Event Sourcing · Hexagonal Architecture · Clean Architecture)

> **80/20 Focus:** CQRS is asked at almost every senior interview. Understand the read/write separation, why it exists, and what problem it solves. Event Sourcing and Hexagonal/Clean Architecture are asked at architect-level interviews.

---

## 1. CQRS — Command Query Responsibility Segregation

### The Problem

```
Traditional CRUD service: one model, one database, serves both reads and writes.

TodoService:
  createTodo(req)      → write: validate user, save record, publish event
  getTodoById(id)      → read: simple fetch by PK
  getTodosByUser(userId) → read: query by FK, possibly join, paginate
  getAnalyticsDashboard  → read: complex aggregation, group by, count

PROBLEM 1: Write model ≠ Read model
  The Todo entity is normalised for writes (no redundancy, referential integrity).
  The analytics dashboard needs a denormalised view (pre-aggregated, fast).
  Forcing both through the same entity is a compromise for both.

PROBLEM 2: Write and read performance characteristics are opposite
  Writes: low volume, need ACID, need consistency, can be slower
  Reads: high volume, need speed, can be eventually consistent, no ACID needed

PROBLEM 3: Scaling writes and reads together
  If reads are 100x more frequent than writes, scaling the write DB for reads is wasteful.
```

### What CQRS does

CQRS separates the **Command** side (writes) from the **Query** side (reads) into two distinct models, potentially two distinct data stores.

```
COMMAND SIDE (writes)          QUERY SIDE (reads)
──────────────────────         ──────────────────────────────────
CreateTodoCommand              getTodoById()
CompleteTodoCommand            getTodosByUser()
DeleteTodoCommand              searchTodos()
                               getAnalyticsDashboard()
        │                              │
   Command Handler              Query Handler
        │                              │
   Write Model                   Read Model
  (normalised DB)            (denormalised DB / ElasticSearch / Redis)
        │                              │
        └──── Kafka event ────────────►│
              TodoCreated              Projection updates read model
              TodoCompleted
```

### The two flavors of CQRS

```
SIMPLE CQRS (same DB, different models)
─────────────────────────────────────────────────────
  One database. Two separate service methods/classes.
  Commands write via Command Handlers → normalised tables.
  Queries read via Query Handlers → possibly different tables/views.

  ✅ Low complexity jump from CRUD
  ✅ No synchronisation problem (same DB = same consistency)
  ✅ Good starting point

FULL CQRS (separate DB, event-driven sync)
─────────────────────────────────────────────────────
  Write DB: PostgreSQL (normalised, ACID)
  Read DB: Redis / ElasticSearch / Cassandra (denormalised, fast)
  Sync: Command side publishes events → Projector consumes → updates read DB

  ✅ Reads and writes scale independently
  ✅ Read model optimised for query patterns (no JOINs needed)
  ✅ Multiple read models from one write model
  ❌ Eventual consistency (read model lags write model briefly)
  ❌ Synchronisation complexity
  ❌ More infrastructure
```

### CQRS in our Todo system

```
Write side (already built in todo-service):
  POST   /api/todos         → CreateTodoCommand  → todoRepository.save()
  PATCH  /api/todos/{id}    → CompleteTodoCommand → todoRepository.save() + outbox event

Read side (what CQRS would add):
  GET    /api/todos/{id}       → TodoReadRepository (Redis cache or separate read table)
  GET    /api/todos?userId=X   → TodoQueryRepository (pre-indexed by userId)
  GET    /api/todos/analytics  → AnalyticsReadModel (pre-aggregated counts)

How they stay in sync:
  CompleteTodo → Kafka event "todo-completed"
  → TodoProjector consumes event
  → Updates Redis: "user:42:stats" { completed: 5, pending: 3 }
  → Next GET /api/todos/analytics/user/42 → Redis hit, no DB query
```

### CQRS Code Structure

```
com.microlearning.todo/
├── command/
│   ├── CreateTodoCommand.java       (data object: title, userId)
│   ├── CompleteTodoCommand.java     (data object: todoId)
│   ├── CreateTodoCommandHandler.java (validates, writes to DB, publishes event)
│   └── CompleteTodoCommandHandler.java
│
├── query/
│   ├── GetTodoByIdQuery.java        (data object: id)
│   ├── GetTodosByUserQuery.java     (data object: userId, page, size)
│   ├── GetTodoByIdQueryHandler.java (reads from read model)
│   └── GetTodosByUserQueryHandler.java
│
├── projection/
│   └── TodoProjector.java          (@KafkaListener — updates read model)
│
└── api/
    └── TodoController.java         (routes to Command or Query handlers)
```

---

## 2. Event Sourcing

### What Event Sourcing is

Instead of storing the **current state** of an entity, store the **sequence of events** that produced that state.

```
TRADITIONAL (store current state):
  todos table:
  | id | title        | completed | completedAt          |
  |----|--------------|-----------|----------------------|
  | 1  | Buy milk     | true      | 2024-01-15 10:30:00  |

  To see current state: SELECT * FROM todos WHERE id=1
  History: LOST. You don't know when it was created, by whom, or what changed.

EVENT SOURCING (store events):
  todo_events table:
  | id | todo_id | event_type     | payload                          | timestamp           |
  |----|---------|----------------|----------------------------------|---------------------|
  | 1  | 1       | TODO_CREATED   | {title:"Buy milk", userId:42}   | 2024-01-15 09:00:00 |
  | 2  | 1       | TODO_COMPLETED | {completedBy:42}                | 2024-01-15 10:30:00 |
  | 3  | 1       | TODO_REOPENED  | {reason:"Not done yet"}         | 2024-01-16 08:00:00 |

  To see current state: replay events 1→2→3 → completed=false (it was reopened)
  History: COMPLETE. Every change is recorded with who, what, when, and why.
```

### How state is reconstructed

```java
// Event sourcing aggregate
public class Todo {
    private Long id;
    private String title;
    private boolean completed;

    // Reconstruct state by replaying events
    public static Todo reconstitute(List<DomainEvent> events) {
        Todo todo = new Todo();
        for (DomainEvent event : events) {
            todo.apply(event);  // each event mutates state
        }
        return todo;
    }

    private void apply(DomainEvent event) {
        if (event instanceof TodoCreatedEvent e) {
            this.id = e.getTodoId();
            this.title = e.getTitle();
            this.completed = false;
        } else if (event instanceof TodoCompletedEvent e) {
            this.completed = true;
        } else if (event instanceof TodoReopenedEvent e) {
            this.completed = false;
        }
    }
}
```

### Event Sourcing + CQRS = natural pairing

```
Write side: Event Store (append-only log of domain events)
  → New command → validate → create domain event → append to store

Read side: Projections (materialized views built from event stream)
  → Listen to event store → build optimised read models
  → Example: "completed todo count per user" projection

This is why CQRS and Event Sourcing are often mentioned together —
each solves a different problem but they complement each other perfectly.
```

### When to use Event Sourcing

```
✅ USE WHEN:
  Complete audit trail required (banking, healthcare, compliance)
  Business needs "time travel" (what was the state at 2023-12-01?)
  Complex business logic with many state transitions
  Need to reprocess historical events with new logic

❌ DON'T USE WHEN:
  Simple CRUD application (massive over-engineering)
  Small team (high operational complexity)
  No audit requirements
  Team unfamiliar with the pattern (steep learning curve)
```

### Event Sourcing problems

```
SNAPSHOT PROBLEM:
  Replaying 10,000 events to get current state is slow.
  Solution: snapshots — periodically store current state + last event ID.
  Replay only events after the snapshot.

EVENT SCHEMA EVOLUTION:
  Event from 2 years ago has a different shape than today's event.
  Need event upcasting — transform old event format to new format on read.
  Always add fields (backwards compatible). Never remove or rename.

EVENTUAL CONSISTENCY:
  Projection update is async. Read model lags write model.
  Same "read your own writes" problem as CQRS full mode.
```

---

## 3. Hexagonal Architecture (Ports & Adapters)

### The Problem

```
Standard layered architecture (Controller → Service → Repository):
  UserController (HTTP) → UserService → UserRepository (JPA/PostgreSQL)

Problems:
  1. Changing from REST to gRPC means touching the service layer
  2. Switching from PostgreSQL to MongoDB means touching the service layer
  3. Testing the service layer requires a running DB or complex mocking
  4. The service layer is tightly coupled to its delivery mechanism AND its persistence

Hexagonal architecture's answer: the core domain should be isolated from 
both the delivery mechanism (HTTP, Kafka, gRPC) and the persistence (DB, Redis, file).
```

### The hexagon

```
                    ┌───────────────────────────────────────────┐
                    │              ADAPTERS (Driving)            │
                    │  RestController  |  KafkaListener  |  CLI  │
                    └──────────────────┬────────────────────────┘
                                       │ calls
                    ┌──────────────────▼────────────────────────┐
                    │           PORTS (Driving)                   │
                    │     UserInputPort (CreateUser, GetUser)     │
                    └──────────────────┬────────────────────────┘
                                       │ implements
                    ┌──────────────────▼────────────────────────┐
                    │                                             │
                    │          DOMAIN / APPLICATION CORE         │
                    │                                             │
                    │     UserService (pure business logic)       │
                    │     User (domain model)                     │
                    │     Domain events                           │
                    │                                             │
                    └──────────────────┬────────────────────────┘
                                       │ calls
                    ┌──────────────────▼────────────────────────┐
                    │           PORTS (Driven)                    │
                    │  UserRepositoryPort  |  NotificationPort    │
                    └──────────────────┬────────────────────────┘
                                       │ implemented by
                    ┌──────────────────▼────────────────────────┐
                    │              ADAPTERS (Driven)              │
                    │  JpaUserRepository  |  KafkaNotification    │
                    │  InMemoryUserRepo   |  EmailNotification     │
                    └───────────────────────────────────────────┘
```

### Concrete example in our system

```java
// PORT (driven) — an interface in the DOMAIN layer
// The domain doesn't know about JPA, H2, or PostgreSQL
public interface UserRepository {              // ← this is a PORT
    User save(User user);
    Optional<User> findById(Long id);
}

// ADAPTER (driven) — lives in the infrastructure layer
// Implements the port using Spring Data JPA
@Repository
public class JpaUserRepositoryAdapter implements UserRepository {
    @Autowired JpaUserJpaRepository jpaRepo;    // Spring Data repository

    @Override
    public User save(User user) {
        return jpaRepo.save(user);              // delegates to JPA
    }
}

// For testing: swap the adapter — no Spring context needed
public class InMemoryUserRepositoryAdapter implements UserRepository {
    private final Map<Long, User> store = new HashMap<>();

    @Override
    public User save(User user) {
        store.put(user.getId(), user);
        return user;
    }
}
```

### Benefits

```
✅ Domain logic testable without Spring, without DB, without Kafka
✅ Swap DB from PostgreSQL to MongoDB → only the adapter changes
✅ Swap REST for gRPC → only the input adapter changes
✅ Domain logic is the stable centre — infrastructure changes around it
✅ True unit tests: instantiate service with InMemoryAdapter → instant, no mocks needed
```

---

## 4. Clean Architecture

### Relation to Hexagonal Architecture

Clean Architecture (Robert C. Martin / Uncle Bob) and Hexagonal Architecture solve the same problem in very similar ways. The core principle is identical: **dependencies point inward**. The inner layers know nothing about the outer layers.

```
                         ┌─────────────────────────────────────┐
                         │         Frameworks & Drivers         │
                         │  (Spring Boot, JPA, Kafka, REST)     │
                         │   ┌─────────────────────────────┐   │
                         │   │      Interface Adapters       │   │
                         │   │  (Controllers, Presenters,   │   │
                         │   │   Gateways, Repositories)    │   │
                         │   │   ┌─────────────────────┐   │   │
                         │   │   │  Application Layer   │   │   │
                         │   │   │  (Use Cases /        │   │   │
                         │   │   │  Command Handlers)   │   │   │
                         │   │   │  ┌───────────────┐  │   │   │
                         │   │   │  │  Domain Layer  │  │   │   │
                         │   │   │  │  (Entities,    │  │   │   │
                         │   │   │  │  Value Objects,│  │   │   │
                         │   │   │  │  Domain Events)│  │   │   │
                         │   │   │  └───────────────┘  │   │   │
                         │   │   └─────────────────────┘   │   │
                         │   └─────────────────────────────┘   │
                         └─────────────────────────────────────┘

DEPENDENCY RULE: Code only points INWARD.
  Outer layer knows about inner layer. Inner layer NEVER knows about outer.
  Domain layer: no imports from Spring, JPA, Kafka, HTTP.
```

### Layer responsibilities

| Layer | Contains | Allowed dependencies |
|---|---|---|
| **Domain** | Entities, Value Objects, Domain Events, Repository interfaces | Nothing external |
| **Application** | Use Cases (CreateUserUseCase), Command/Query Handlers | Domain only |
| **Interface Adapters** | Controllers, Presenters, Repository implementations | Application + Domain |
| **Frameworks** | Spring Boot config, JPA entities, Kafka config | Everything |

### Practical folder structure

```
com.microlearning.user/
├── domain/
│   ├── User.java                    ← Entity (pure Java, no @Entity)
│   ├── UserId.java                  ← Value Object
│   ├── UserCreatedEvent.java        ← Domain Event
│   └── UserRepository.java         ← Repository Port (interface)
│
├── application/
│   ├── CreateUserUseCase.java       ← Use Case (orchestrates domain logic)
│   └── GetUserUseCase.java
│
├── adapter/
│   ├── in/
│   │   ├── web/UserController.java  ← REST adapter (calls use case)
│   │   └── kafka/UserEventConsumer  ← Kafka adapter
│   └── out/
│       ├── persistence/
│       │   ├── UserJpaEntity.java   ← JPA entity (@Entity goes here, not in domain)
│       │   └── UserJpaAdapter.java  ← implements UserRepository using JPA
│       └── messaging/
│           └── KafkaEventPublisher  ← implements EventPublisher port
│
└── infrastructure/
    └── config/
        ├── BeanConfig.java          ← Spring @Configuration
        └── KafkaConfig.java
```

### Hexagonal vs Clean vs Layered — comparison

| Aspect | Layered (Traditional) | Hexagonal | Clean Architecture |
|---|---|---|---|
| Domain isolation | Low | High | High |
| Testability | Needs Spring | Pure Java unit tests | Pure Java unit tests |
| Complexity | Low | Medium | High |
| Learning curve | Low | Medium | High |
| Good for | Small services | Medium services | Large, complex domains |
| Main benefit | Simple | Ports & Adapters metaphor | Strict layer enforcement |

---

## 5. Interview Questions

**Q1: What is CQRS and why would you use it?**
> CQRS separates the write model (Commands) from the read model (Queries). Write operations go through command handlers that enforce business rules and update a normalised write store. Read operations go through query handlers against a denormalised read model optimised for the specific query. Use it when: read and write models have significantly different shapes, reads are much more frequent than writes (scale them independently), you need complex read projections (aggregations, full-text search) without impacting write performance.

**Q2: What is Event Sourcing? How does it differ from traditional persistence?**
> Traditional persistence stores the current state of an entity. Event Sourcing stores the sequence of events that produced that state. To get current state, replay all events. This gives you complete audit history, time-travel queries (what was the state at date X?), and the ability to create new read projections by replaying history with new logic. The downside: complexity, event schema evolution challenges, and performance (mitigated with snapshots). Best suited for domains where audit trail is a first-class requirement: banking, healthcare, compliance.

**Q3: What is Hexagonal Architecture?**
> Hexagonal Architecture (Ports & Adapters) isolates the domain and business logic from the delivery mechanism (HTTP/Kafka/gRPC) and persistence (PostgreSQL/MongoDB/Redis). The domain defines interfaces (Ports). External components implement those interfaces (Adapters). The core rule: the domain depends on nothing external. Benefits: domain is testable with pure Java (no Spring context needed), infrastructure can be swapped without touching business logic, multiple delivery mechanisms can use the same domain.

**Q4: Why do CQRS and Event Sourcing often appear together?**
> They solve complementary problems. Event Sourcing produces an event log (the write model). But querying an event log directly is slow (replay N events for every read). CQRS solves this: consume the event log and build denormalised projections (the read model). The event store is the source of truth; projections are derived views. Together they give you: immutable write history (Event Sourcing) + fast, optimised reads (CQRS projections).

---

## 6. Tricky Interview Questions

**Q: "I have a CQRS system where the write DB just committed a Todo as completed. The user immediately requests a GET for that todo. The read model hasn't been updated yet. What does the user see?"**
> The user may see the todo as incomplete — this is the eventual consistency window inherent in full CQRS. Solutions: (1) Accept it — show a "processing" indicator, tell users the update will be reflected shortly. (2) Read-your-own-writes: after a successful command, for this specific user, route their next read to the write DB for a brief window (e.g., 5 seconds). (3) Optimistic UI: update the client UI immediately without waiting for the server to reflect it — the server will catch up. The right choice depends on the business requirement.

**Q: In Event Sourcing, a bug in a projection caused incorrect data to be stored in the read model for 3 months. How do you fix it?**
> This is one of Event Sourcing's greatest strengths. The event store (write model) is the source of truth and was never wrong. Steps: (1) Fix the bug in the projector. (2) Reset the read model to empty. (3) Replay all events from the beginning of time through the fixed projector. (4) Read model is now correct. This operation is called "projection rebuild" and is impossible with traditional CRUD (you'd have lost the original data).

---

## 7. Quick Revision Cheat Sheet

```
CQRS
  Command = write (CreateTodo, CompleteTodo) → write model (normalised DB)
  Query   = read  (getTodoById, search)      → read model (Redis, Elasticsearch)
  Sync between: Kafka events → Projector → updates read model
  Trade-off: eventual consistency on read model
  Simple CQRS: same DB, different models (low complexity)
  Full CQRS:   different DBs, event-driven sync (high complexity)

EVENT SOURCING
  Store events, not state. Current state = replay all events.
  Append-only event store. Never update or delete events.
  Benefits: full audit trail, time travel, rebuild projections
  Problems: event schema evolution, performance (use snapshots)
  Pair with CQRS: event store (write) + projections (read)
  Use when: audit is critical (banking, healthcare, compliance)

HEXAGONAL ARCHITECTURE
  Core: Domain + Application (pure Java, zero framework imports)
  In: Driving adapters (REST controller, Kafka listener) → call Ports
  Out: Driven adapters (JPA repo, Kafka producer) ← implement Ports
  Benefit: swap DB/transport without touching domain
  Test: use InMemory adapters — no Spring context needed

CLEAN ARCHITECTURE
  Dependency Rule: code points INWARD only
  Domain → Application → Adapters → Frameworks
  Inner layers know NOTHING about outer layers
  Domain: Entities, Value Objects, Repository interfaces (no @Entity, no Spring)
  Application: Use Cases / Command Handlers
  Adapters: Controllers, Repository implementations
  Framework: Spring config, JPA entities, Kafka config
```
