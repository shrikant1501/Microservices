# Service Decomposition Decision Record
# Todo Application — Microservices Target Architecture
# Phase 2 Output Document

## Decision

Split the Todo Monolith into 3 microservices aligned to 3 Bounded Contexts
discovered through domain analysis.

---

## Bounded Context Map

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CONTEXT MAP — TODO SYSTEM                        │
│                                                                     │
│  ┌──────────────────┐  Customer/Supplier  ┌──────────────────────┐ │
│  │  USER CONTEXT    │◄────────────────────│   TODO CONTEXT       │ │
│  │  (Upstream)      │   REST: GET /users  │   (Downstream)       │ │
│  └──────────────────┘                     └──────────────────────┘ │
│           │                                         │               │
│           │ publishes                               │ publishes     │
│           │ UserCreated                             │ TodoCompleted │
│           ▼                                         ▼               │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                     KAFKA MESSAGE BUS                          │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                               │                                     │
│                               │ consumes both events                │
│                               ▼                                     │
│                   ┌──────────────────────┐                         │
│                   │  NOTIFICATION CONTEXT│                         │
│                   │  (Pure Downstream)   │                         │
│                   └──────────────────────┘                         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Service Definitions

### user-service
| Property | Value |
|---|---|
| Bounded Context | User Management |
| Business Capability | Identity and profile management |
| Subdomain Type | Supporting |
| Aggregate Root | User |
| Database | users_db (owns: users table) |
| REST API | POST /api/users, GET /api/users/{id}, GET /api/users |
| Events Published | UserCreated |
| Events Consumed | (none) |
| Dependencies | (none) |
| Port | 8081 |
| Team | Platform Team |

### todo-service
| Property | Value |
|---|---|
| Bounded Context | Task Management |
| Business Capability | Todo lifecycle management |
| Subdomain Type | Core (primary value of this app) |
| Aggregate Root | Todo |
| Database | todos_db (owns: todos table) |
| REST API | POST /api/todos, GET /api/todos/{id}, GET /api/todos?userId=, PATCH /api/todos/{id}/complete |
| Events Published | TodoCompleted |
| Events Consumed | (none required — uses REST to validate user) |
| Dependencies | user-service (via REST — validate userId on create) |
| Port | 8082 |
| Team | Product Team |

### notification-service
| Property | Value |
|---|---|
| Bounded Context | Communications |
| Business Capability | Notification delivery |
| Subdomain Type | Generic (could eventually be replaced by SaaS like SendGrid) |
| Aggregate Root | Notification (audit log) |
| Database | notifications_db (owns: notifications table) |
| REST API | None (event-consumer only) |
| Events Published | NotificationSent |
| Events Consumed | UserCreated, TodoCompleted |
| Dependencies | (none — purely reactive) |
| Port | 8083 |
| Team | Platform Team |

---

## Cross-Service Data Access Decisions

### Decision 1: todo-service needs userName for TodoResponse

**Options considered:**
- A: Call user-service REST API on every GET /todos request
- B: Store userName snapshot in todos table at creation time
- C: Sync via UserUpdated event into local cache

**Decision:** Option A (Synchronous REST) for Phase 3 (simplest, demonstrates the pattern).
             Option B considered for Phase 4 evolution when Kafka is available.

**Rationale:** Stale user names on old todos is acceptable in our domain.
               Phase 4 will evaluate moving to Option C.

### Decision 2: notification-service needs user email for welcome email

**Options considered:**
- A: notification-service calls user-service REST API to get the email
- B: UserCreated event includes the email in the payload

**Decision:** Option B — embed email in the event payload.

**Rationale:** Notification service becomes fully autonomous. If user-service is
               down when the event is consumed, the notification still has the email.

---

## What Is NOT Changing

- The REST API contracts remain identical (same paths, same request/response shapes)
- The business logic remains identical
- The H2 database is split into 3 logical databases (separate DataSource per service)

## What IS Changing (Phase 3)

- 3 separate Spring Boot applications (3 separate JVMs, 3 separate ports)
- TodoService's UserRepository injection → replaced with REST HTTP call
- NotificationService's direct injection → replaced with Kafka events (Phase 4)
- Each service has its own application.properties
