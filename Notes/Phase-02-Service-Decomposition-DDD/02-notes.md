# Phase 2 — Service Decomposition & Domain-Driven Design

> **Role:** Senior Software Architect | Java Backend Engineer | Technical Interviewer
> **Prerequisite:** Phase 1 complete — you understand WHY microservices exist.
> **Goal:** Learn HOW to split a system into the right services — the most critical and most commonly wrong decision in microservices.

---

## 1. Goal of This Phase

By the end of Phase 2 you will:

- Understand **why the decomposition decision is the most important** in microservices
- Know the **three decomposition strategies** and when to use each
- Understand **Domain-Driven Design (DDD)** core concepts: Domain, Subdomain, Bounded Context, Ubiquitous Language
- Be able to **identify service boundaries** using the Bounded Context pattern
- Know the difference between **strategic DDD** (what to build) and **tactical DDD** (how to build it)
- Apply decomposition thinking to the **Todo application**
- Spot and avoid the **"Distributed Monolith"** anti-pattern

---

## 2. Business Problem We Are Solving

You are now the lead architect at a growing company. The monolith is causing pain.
The CTO says: *"We need to break this into microservices."*

The first question every junior developer asks is: **"How small should a microservice be?"**

The right question an architect asks is: **"What are the natural boundaries of this business domain?"**

Get the decomposition wrong and you create a **Distributed Monolith** — a system that has all the operational complexity of microservices but none of the independence. Services that:
- Must be deployed together
- Share databases
- Make synchronous calls to 8 other services just to return one response
- Cannot be changed without coordinating 5 teams

This is worse than the original monolith. The decomposition decision is irreversible in practice — extracting a wrongly split service costs months.

---

## 3. Concepts to Learn

### 3.1 The Three Decomposition Strategies

#### Strategy 1 — Decompose by Business Capability

A **business capability** is something the business does to generate value. It is stable. It doesn't change with technology or implementation.

```
E-Commerce Business Capabilities:
┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  User Management│  │ Product Catalogue │  │ Order Management │
│  (Identity)     │  │ (Inventory)       │  │ (Fulfilment)     │
└─────────────────┘  └──────────────────┘  └──────────────────┘
┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  Payments       │  │ Notifications     │  │ Reviews & Ratings│
│  (Finance)      │  │ (Comms)           │  │ (Engagement)     │
└─────────────────┘  └──────────────────┘  └──────────────────┘
```

Each capability → one candidate microservice.

**When to use:** When you have a clear org chart and each team owns a business function.

#### Strategy 2 — Decompose by Subdomain (DDD)

Identify subdomains of the business problem, classify them, and map services to subdomain boundaries.

This is the most sophisticated and most correct approach. We will deep-dive this in section 3.3.

**When to use:** When the domain is complex and the business logic is non-trivial.

#### Strategy 3 — Decompose by Transactions (Strangler Fig)

For existing monoliths: identify the seams in the monolith by looking at natural transaction boundaries. Extract at those seams incrementally.

```
STRANGLER FIG PATTERN:

      Old Monolith                 New Services
    ┌─────────────┐               ┌───────────────┐
    │ All traffic │               │  New Service  │
    │   handled   │  ──── over ──▶│  handles NEW  │
    │  by Mono    │    time       │   requests    │
    └─────────────┘               └───────────────┘
         ▲                              ▲
         └── gradually replaced ────────┘
```

**When to use:** Incrementally migrating an existing monolith. Never attempt a big-bang rewrite.

---

### 3.2 What is Domain-Driven Design (DDD)?

**Domain-Driven Design** is a software development philosophy introduced by Eric Evans (2003) in the book *"Domain-Driven Design: Tackling Complexity in the Heart of Software."*

The core idea:
> *"The domain model — the business problem and its logic — should be the primary driver of software design. Software should speak the language of the business."*

DDD solves a specific problem: **when complex business logic leaks into the wrong layers** (e.g., business rules in SQL queries, validation in the database, domain logic in REST controllers).

#### Two Levels of DDD

| Level | What it is | Used for |
|-------|-----------|----------|
| **Strategic DDD** | How to carve the system into parts | Finding service boundaries (our focus in Phase 2) |
| **Tactical DDD** | Patterns for implementing the domain model | Entities, Value Objects, Aggregates, Repositories (used inside a service) |

> **Interview Insight:** Most candidates only know tactical DDD patterns (Entity, Repository). Senior architects know strategic DDD — specifically Bounded Contexts. That is what interviewers really want to hear.

---

### 3.3 Strategic DDD — The Concepts That Drive Decomposition

#### Concept 1 — Domain

The **domain** is the business problem you are solving. It is the complete subject area of the application.

```
Example: An e-commerce company's domain is "online retail"
Sub-problems within that domain:
  - Managing customer accounts
  - Managing products
  - Processing orders
  - Handling payments
  - Sending notifications
  - Managing deliveries
```

#### Concept 2 — Subdomain

A **subdomain** is a part of the domain. Subdomains are discovered by asking: *"What distinct business problems exist within this domain?"*

There are three types:

| Type | Description | Examples | Priority |
|------|-------------|---------|----------|
| **Core Domain** | What makes the business unique. The competitive advantage. Invest most here. | Recommendation engine (Netflix), Pricing algorithm (Uber), Route optimization (FedEx) | Highest |
| **Supporting Subdomain** | Needed but not unique. You build it, but it doesn't differentiate. | Order Management, User Management | Medium |
| **Generic Subdomain** | Common problems. Buy off-the-shelf or use SaaS. | Email/SMS, Authentication (use Auth0/Keycloak), Payment (use Stripe) | Buy, don't build |

> **Interview Insight:** When an interviewer asks "how do you decide what to build vs buy?", the answer is: "I identify whether the problem is in the Core, Supporting, or Generic subdomain. Generic subdomains should be purchased or integrated, not built from scratch."

#### Concept 3 — Ubiquitous Language

The **Ubiquitous Language** is a shared vocabulary used by both business people and developers within a bounded context.

```
WRONG:
  Business says: "Customer places an Order"
  Developer models: UserEntity creates a TransactionRecord
  
RIGHT (Ubiquitous Language):
  Business says: "Customer places an Order"
  Developer models: Customer places an Order
  → class Customer, class Order, order.place()
```

The code should read like the business speaks.

**Why it matters for microservices:** Different services can use the same word to mean different things. A "Customer" in the Order Service is someone who placed an order. A "Customer" in the Payments Service is someone with a billing profile. Same word, different meaning, different data — this is why they must be separate services.

#### Concept 4 — Bounded Context ⭐ (THE most important concept)

A **Bounded Context** is an explicit boundary within which a domain model applies. Within that boundary, every term has a single, unambiguous meaning.

```
╔══════════════════════════════╗    ╔══════════════════════════════╗
║   ORDER BOUNDED CONTEXT      ║    ║   PAYMENT BOUNDED CONTEXT    ║
║                              ║    ║                              ║
║  Customer = {                ║    ║  Customer = {                ║
║    id, name, email,          ║    ║    id, billingAddress,       ║
║    shippingAddress,          ║    ║    creditCard, paymentHistory║
║    orderHistory              ║    ║  }                           ║
║  }                           ║    ║                              ║
║                              ║    ║  "Customer" here means       ║
║  "Customer" here means       ║    ║  someone with a payment      ║
║  someone who shops           ║    ║  profile                     ║
╚══════════════════════════════╝    ╚══════════════════════════════╝
           │                                     │
           └──── only communicate via API ───────┘
                 (never share the DB table)
```

**The mapping rule:** *One Bounded Context = One Microservice* (or sometimes a few small ones within the same team).

This is the answer to "how do you decide service boundaries?" in any interview.

---

### 3.4 Context Map — How Bounded Contexts Relate

A **Context Map** documents how bounded contexts communicate and depend on each other.

```
Context Map for Todo Application (Evolved):

┌─────────────────┐         ┌─────────────────┐
│  User Context   │◄────────│  Todo Context   │
│  (Upstream /    │  REST   │  (Downstream /  │
│   Provider)     │  API    │  Consumer)      │
└─────────────────┘         └─────────────────┘
        │                           │
        │ publishes                 │ publishes
        │ UserCreated event         │ TodoCompleted event
        ▼                           ▼
┌───────────────────────────────────────────────┐
│            Message Bus (Kafka)                 │
└───────────────────────────────────────────────┘
        │
        ▼
┌─────────────────┐
│  Notification   │
│  Context        │
│  (Downstream /  │
│  Consumer)      │
└─────────────────┘
```

Relationship types between contexts:

| Relationship | Meaning |
|-------------|---------|
| **Customer / Supplier** | Upstream (supplier) provides API, downstream (customer) consumes it |
| **Conformist** | Downstream adopts upstream's model as-is (no translation layer) |
| **Anti-Corruption Layer (ACL)** | Downstream translates upstream's model to its own (protects domain purity) |
| **Published Language** | Upstream defines a well-documented API that multiple consumers use |
| **Shared Kernel** | Two contexts share a small subset of the domain model (risky — avoid in microservices) |

> **Interview Insight:** When asked "how do you prevent upstream service changes from breaking downstream services?", the answer is: "Anti-Corruption Layer — the downstream service has a translation layer that maps the upstream's API model to its own internal domain model. The internal domain is protected from upstream changes."

---

### 3.5 Tactical DDD — Inside a Bounded Context

Once you have your service boundaries (strategic DDD), you need to model the internals. This is tactical DDD.

| Pattern | What it is | Example |
|---------|-----------|---------|
| **Entity** | Object with a unique identity that persists over time | User (has id, can change email) |
| **Value Object** | Immutable object defined by its attributes, no identity | Money(100, "USD"), Address("123 Main St") |
| **Aggregate** | Cluster of Entities + Value Objects treated as a single unit. Has one Aggregate Root. | Order (root) + OrderItems + ShippingAddress |
| **Aggregate Root** | The single entry point for the aggregate. External code can only reference the root. | Order is the root — you never modify OrderItem directly |
| **Domain Event** | Records that something significant happened in the domain | OrderPlaced, PaymentProcessed, UserRegistered |
| **Repository** | Abstracts persistence for aggregates | OrderRepository.save(order) |
| **Domain Service** | Business logic that doesn't belong to any single entity | PricingService.calculateDiscount(order, customer) |
| **Application Service** | Orchestrates domain objects to fulfil use cases. Thin layer. | OrderApplicationService.placeOrder(request) |

#### The Aggregate Rule
The most important tactical DDD rule for microservices:

> **"Each service should own exactly one or a few aggregates. External services should reference aggregates only by ID, never by direct object reference."**

```
✅ CORRECT:
Order Service owns: Order aggregate (Order + OrderItems)
Order knows about: userId (Long) — just an ID reference
Order does NOT hold: a User object, a UserRepository

❌ WRONG:
Order holds a full User object
Order calls UserRepository directly
(This is what our monolith currently does — it will break in microservices)
```

---

### 3.6 Identifying Bounded Contexts — Event Storming

**Event Storming** is a collaborative workshop technique (Alberto Brandolini, 2013) for discovering bounded contexts by mapping domain events on a timeline.

**How it works:**
1. Gather domain experts and developers in a room with sticky notes
2. Write every **Domain Event** that happens in the system (orange stickies)
   - "UserRegistered", "TodoCreated", "TodoCompleted", "EmailSent"
3. Add **Commands** that trigger events (blue stickies)
   - "RegisterUser" → "UserRegistered"
4. Add **Aggregates** that handle commands (yellow stickies)
5. Look for **natural groupings** — these are your bounded contexts

```
Event Storming Result for Todo App:

  RegisterUser → [User] → UserRegistered
  CreateTodo   → [Todo] → TodoCreated
  CompleteTodo → [Todo] → TodoCompleted
  SendEmail    → [Notification] → EmailSent

Natural groupings:
  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────┐
  │   USER CONTEXT  │  │  TODO CONTEXT   │  │ NOTIFICATION CTX │
  │  User aggregate │  │  Todo aggregate │  │ Notification agg │
  └─────────────────┘  └─────────────────┘  └──────────────────┘
```

---

## 4. Applying Decomposition to the Todo Application

### Current Monolith — What We Have

```
todo-monolith/
├── user/         → User aggregate, CRUD APIs
├── todo/         → Todo aggregate, depends on user/
└── notification/ → Email logic, called directly by UserService
```

### Questions for Each Module

| Question | User | Todo | Notification |
|----------|------|------|--------------|
| Different scaling need? | Low scale | Medium scale | Burst (async) |
| Different deployment cadence? | Infrequent | Frequent (feature-heavy) | Infrequent |
| Clear stable boundary? | ✅ Yes | ✅ Yes | ✅ Yes |
| Different team? | Could be | Could be | Could be |
| Different tech need? | No | No | Maybe (3rd party) |

**Verdict:** All three modules are strong candidates for extraction.

### The New Architecture (Target)

```
                            ┌──────────────────────┐
                            │      API Gateway      │
                            │  (Spring Cloud GW)    │
                            └──────────┬───────────┘
                ┌───────────────────────┼──────────────────────┐
                ▼                       ▼                       ▼
    ┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐
    │    user-service     │ │    todo-service      │ │ notification-service│
    │   :8081             │ │   :8082              │ │   :8083             │
    │                     │ │                      │ │                     │
    │  /api/users/**      │ │  /api/todos/**        │ │  Consumes events    │
    │  ┌──────────────┐   │ │  ┌───────────────┐   │ │  (no REST API)      │
    │  │  H2 / Postgres│  │ │  │ H2 / Postgres │   │ │                     │
    │  └──────────────┘   │ │  └───────────────┘   │ │                     │
    └─────────────────────┘ └─────────────────────┘ └─────────────────────┘
                                     │ HTTP call to                │
                                     │ user-service                │ consumes
                                     │ (Phase 3)                   │ Kafka events
                                     ▼                             │ (Phase 4)
                           ┌──────────────────┐                   │
                           │  Service Registry │                   │
                           │  (Eureka - Ph 5)  │◄──────────────────┘
                           └──────────────────┘
```

### Why NOT extract more granularly?

A common mistake is to create:
- `UserReadService` and `UserWriteService` as separate services
- `TodoCreateService`, `TodoCompleteService`, `TodoQueryService`

This violates cohesion. The Todo domain belongs together — reading and writing todos share the same business rules and the same database. Splitting by CRUD operation is splitting by technical concern, not business boundary. You've just created a distributed monolith.

---

## 5. The Distributed Monolith Anti-Pattern

A **Distributed Monolith** is the worst possible outcome: you have microservices on paper, but in reality:

```
SIGNS OF A DISTRIBUTED MONOLITH:

1. Service A calls B calls C calls D synchronously for every request
   → 4 network hops, cascading failures, tight runtime coupling

2. Services share a database
   → Schema changes require coordinating all teams

3. Services must be deployed in a specific order
   → Deployment coupling remains

4. Services share a common library with business logic
   → A change in the library forces all services to upgrade simultaneously

5. One failing service brings down all others
   → No failure isolation

RESULT: Operational complexity of microservices + tight coupling of monolith
        = Worst of both worlds
```

**How to avoid it:**
- Own your data (no shared DB)
- Communicate via events for non-critical flows (async)
- Design for failure (circuit breakers)
- Deploy independently (no deployment order)

---

## 6. Step-by-Step: Documenting the Decomposition

Before writing any code, an architect produces a **Service Decomposition Document**.

For our Todo system:

```
SERVICE: user-service
━━━━━━━━━━━━━━━━━━━━
Bounded Context : User Management
Business Capability: Identity and profile management
Aggregate Root  : User
Owns            : users table
REST API        : POST /api/users, GET /api/users/{id}
Events Published: UserCreated
Events Consumed : (none in Phase 2)
Team            : Platform Team
Port            : 8081

SERVICE: todo-service  
━━━━━━━━━━━━━━━━━━━━
Bounded Context : Task Management
Business Capability: Todo lifecycle management
Aggregate Root  : Todo
Owns            : todos table
REST API        : POST /api/todos, GET /api/todos/{id}, PATCH /api/todos/{id}/complete
Events Published: TodoCompleted
Events Consumed : UserCreated (to cache user data - optional)
Depends on      : user-service (REST call to validate userId)
Team            : Product Team
Port            : 8082

SERVICE: notification-service
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Bounded Context : Communications
Business Capability: Notification delivery
Aggregate Root  : Notification (log of sent notifications)
Owns            : notifications table
REST API        : None (event-driven only)
Events Published: NotificationSent
Events Consumed : UserCreated, TodoCompleted
Team            : Platform Team
Port            : 8083
```

---

## 7. Internal Working — How Bounded Context Isolation Works

When `todo-service` needs to display "created by Alice Smith" on a Todo response, it has THREE architectural options:

### Option A — Synchronous REST Call (Phase 3)
```
GET /api/todos/42
  ↓
todo-service: loads Todo from its DB
  ↓
todo-service: calls GET http://user-service/api/users/7
  ↓ (network hop — can fail, adds latency)
todo-service: merges User data into TodoResponse
  ↓
Returns TodoResponse with userName = "Alice Smith"
```
**Pro:** Always up-to-date user data
**Con:** Adds latency. If user-service is down, todo query fails.

### Option B — Denormalized Snapshot (Cache in Todo's DB)
```
When Todo is created:
  todo-service stores: { userId: 7, userName: "Alice Smith" }
  (snapshot of user name at creation time)

GET /api/todos/42
  ↓
todo-service: loads Todo from its DB (has userName already)
  ↓
Returns TodoResponse — NO call to user-service
```
**Pro:** No dependency on user-service at read time. Faster.
**Con:** If user changes their name, todos still show the old name.
**When to use:** When staleness is acceptable (display names on old records).

### Option C — Event-Driven Sync (Phase 4)
```
user-service publishes UserUpdated event → Kafka
  ↓
todo-service consumes event
  ↓
todo-service updates its local copy of userName
```
**Pro:** Eventually consistent. todo-service is never blocked by user-service.
**Con:** Window of inconsistency. More complex.

> **Interview question:** "How do you handle data from another service?" This A/B/C framework is your answer. State the trade-offs and recommend based on consistency requirements.

---

## 8. Real-World Use Cases

| Company | Decomposition approach |
|---------|----------------------|
| **Amazon** | Decomposed by business capability (1 service = 1 "two-pizza team") |
| **Netflix** | Started with a few large services (streaming, account, browse), then further decomposed as teams grew |
| **Uber** | Event Storming-style discovery of domain boundaries as cities and features expanded |
| **Spotify** | "Squads" own services — Conway's Law deliberately applied. A Squad owns 1–3 services within their bounded context. |

---

## 9. Trade-offs and Alternative Approaches

| Approach | Pro | Con |
|----------|-----|-----|
| **DDD Bounded Context** | Correct business alignment, stable boundaries | Requires deep domain knowledge, Event Storming workshops |
| **By Business Capability** | Fast, org-aligned | May not reflect true domain complexity |
| **By team size** | Simple rule: 1 team = 1 service | Teams change, domains don't |
| **By data (separate DB first)** | Pragmatic, visible seams | DB separation is harder than code separation |

---

## 10. Common Mistakes

| Mistake | Consequence |
|---------|-------------|
| **Too fine-grained decomposition** | Chatty services (100 REST calls per request), distributed monolith |
| **Splitting by technical layer** | UserControllerService, UserRepositoryService — pointless |
| **Shared database between services** | Tightest coupling possible — defeats the purpose |
| **Not doing Event Storming first** | Guessing at boundaries, wrong boundaries that are expensive to fix |
| **Modelling services by UI screens** | Screens change constantly — services become unstable |
| **Circular service dependencies** | A calls B calls A — impossible to deploy independently |

---

## 11. Production Best Practices

1. **Run Event Storming before writing code** — get domain experts in the room.
2. **Each service = one Aggregate Root** as a starting rule. Split further only when there is a clear reason.
3. **Never share a database schema** between services. Not even a read replica.
4. **Document your Context Map** — know who calls whom and why.
5. **Prefer async (events) over sync (REST) between bounded contexts** where possible.
6. **Start coarse-grained** — "User Service, Order Service, Payment Service" not "UserRead, UserWrite, UserNotify...".
7. **Watch for the anti-corruption layer need** — when you consume a badly-designed upstream API, wrap it in a translator.

---

## 12. Frequently Asked Interview Questions

**Q1. How do you decide the boundaries of a microservice?**

> I use the Bounded Context pattern from Domain-Driven Design. A bounded context defines a boundary within which a domain model has a single, consistent meaning. I map one Bounded Context to one microservice (or a small cluster of related services owned by one team). I discover these boundaries through Event Storming — mapping domain events on a timeline and looking for natural clusters of events, commands, and aggregates. The result is a service boundary that is aligned to the business domain, not to technical layers.

**Q2. What is a Bounded Context?**

> A Bounded Context is an explicit boundary within which a particular domain model is valid and consistent. Within that boundary, every term has one unambiguous meaning. For example, "Customer" in an Order Service means "someone who shops" (with shipping address, order history), but "Customer" in a Payment Service means "someone with a billing profile" (with credit card, payment history). These are different models of the same real-world concept. The Bounded Context acknowledges this and keeps the models separate rather than forcing a single "Customer" class to serve both purposes.

**Q3. What is the difference between Core, Supporting, and Generic subdomains?**

> The Core subdomain is the unique business differentiator — it is what makes the company valuable and competitive. You invest most engineering effort here and build it in-house. The Supporting subdomain is necessary but not differentiating — you build it but you won't win in the market with it. The Generic subdomain is a solved problem — authentication, email, payments. You should buy or integrate these (Stripe, Auth0, SendGrid) rather than build them. This classification drives the build-vs-buy decision.

**Q4. What is Event Storming?**

> Event Storming is a collaborative discovery workshop where domain experts and engineers map out the business process by placing domain events on a timeline. Domain events are things that happened in the past (UserRegistered, OrderPlaced, PaymentProcessed). By grouping related events, commands, and aggregates, teams discover the natural boundaries of the domain — which become bounded contexts and ultimately service boundaries. It is a fast way to get shared understanding of a complex domain without writing code.

**Q5. What is the difference between an Entity and a Value Object in DDD?**

> An Entity has identity — it is uniquely identifiable and its identity persists even when its attributes change. A User is an Entity (same user even after they change their email). A Value Object has no identity — it is defined entirely by its attributes and is immutable. Money(100, "USD") is a Value Object — two Money objects with the same amount and currency are equal; there's no "which one". Value Objects should be immutable and compared by value, not by reference.

---

## 13. Tricky Interview Questions

**Q. Can two microservices share a database?**

> No — not in any meaningful microservices architecture. The moment two services share a database, you've coupled them at the data layer. A schema change in the shared table forces both services to be tested and deployed together. You've recreated deployment coupling and destroyed service autonomy — which were the primary reasons to use microservices. If you need data from another service, call its API or consume its events. The rule is: one service, one database, no sharing.

**Q. What is the right size for a microservice?**

> There is no correct answer in bytes or lines of code. The right size is: *small enough to be owned and deployed by one team, large enough to represent a meaningful business capability*. Sam Newman's rule: a service should be able to be rewritten by one team in two weeks if needed. Martin Fowler says: size should be determined by the team's ability to manage it, not by a line count. The most useful heuristic: one Bounded Context = one service.

**Q. What is a Distributed Monolith and why is it dangerous?**

> A Distributed Monolith is a system that has been split into multiple deployable units but retains all the coupling of a monolith. Services share databases, call each other synchronously in long chains, must be deployed in a specific order, and cannot fail independently. It is dangerous because it combines the operational complexity of microservices (many deployments, distributed tracing, network failures) with the tight coupling of a monolith (no autonomy, no independent scaling, cascading failures). It is harder to operate than either a clean monolith or a clean microservices system.

**Q. What is Conway's Law and the Inverse Conway Manoeuvre?**

> Conway's Law states that a system's architecture mirrors the communication structure of the organization that built it. If you have one large team, you build a monolith. If you have three siloed teams, you build three tightly-coupled layers. The Inverse Conway Manoeuvre is deliberately designing your organization around the desired architecture. If you want three independent microservices, you create three teams that own one service each and communicate only through defined APIs — the architecture then emerges naturally from the team structure.

---

## 14. Scenario-Based Interview Questions

**Scenario 1:** You are asked to decompose a hospital management system into microservices. How do you approach it?

> I would start with Event Storming — bringing together hospital administrators, doctors, nurses, and billing staff to map the domain events: PatientAdmitted, AppointmentScheduled, PrescriptionIssued, BillGenerated, InsuranceClaimFiled. From these events I would identify the natural clusters: Patient Management (registration, demographics), Clinical Operations (appointments, prescriptions, lab results), Billing (invoices, payments, insurance), Notifications (reminders, alerts). I would then classify each: Clinical Operations is the Core Domain (what makes the hospital unique), Billing may be Supporting, Notifications is Generic (integrate with a service). Each cluster becomes a bounded context and a microservice candidate.

**Scenario 2:** A team has extracted 15 microservices from a monolith. Every API call requires chaining through 8 services. Performance has degraded and a single service failure brings down the whole system. What went wrong?

> This is a Distributed Monolith caused by over-decomposition and synchronous call chains. The services were likely split too fine-grained (by technical operation rather than business domain) or the bounded contexts were not correctly identified. Solutions: (1) Re-aggregate services that always move together back into one service — accept the duplication cost for correctness. (2) Replace synchronous call chains with event-driven communication for non-critical paths. (3) Add circuit breakers to prevent cascading failures. (4) Consider the BFF (Backend for Frontend) pattern — an API composition layer that aggregates the calls client-side rather than chaining service-to-service.

---

## 15. Small Exercise

Before moving to Phase 3, complete this exercise:

1. Draw (on paper or a notes file) the Context Map for the Todo application with all three services: user-service, todo-service, notification-service. Show which is upstream, which is downstream, and what data flows between them.

2. Answer: *"If a user changes their display name, should the todos they created immediately reflect the new name? What are the architectural implications of your answer?"*

3. Identify one example of a **Value Object** in the Todo domain. Why is it a Value Object and not an Entity?

---

## 16. Quick Revision Cheat Sheet

```
DDD KEY CONCEPTS
 └─ Domain: the business problem
 └─ Subdomain: part of the domain
    ├─ Core: competitive advantage — build
    ├─ Supporting: necessary — build
    └─ Generic: solved problem — buy/integrate
 └─ Ubiquitous Language: shared vocabulary in code and conversation
 └─ Bounded Context: boundary within which a model is consistent
    └─ = One Microservice (usually)
 └─ Context Map: documents how bounded contexts relate

DECOMPOSITION STRATEGIES
 └─ By Business Capability (org-aligned)
 └─ By Subdomain / Bounded Context (domain-aligned — BEST)
 └─ Strangler Fig (for existing monoliths)

TACTICAL DDD (inside a service)
 └─ Entity: has identity (User, Order)
 └─ Value Object: no identity, immutable (Money, Address)
 └─ Aggregate: cluster of entities, one root
 └─ Aggregate Root: single entry point for the cluster
 └─ Domain Event: "something happened" (UserRegistered)
 └─ Repository: persistence abstraction for aggregates

DISTRIBUTED MONOLITH (anti-pattern — avoid)
 └─ Services deployed separately BUT:
    - share database
    - long synchronous call chains
    - must deploy in order
    - cascading failures
 └─ Worse than either clean monolith or clean microservices

CROSS-SERVICE DATA ACCESS (3 options)
 └─ A: Synchronous REST call (fresh data, latency + coupling risk)
 └─ B: Denormalized snapshot (fast, stale data acceptable)
 └─ C: Event-driven sync (eventually consistent, async)

CONWAY'S LAW
 └─ Architecture mirrors org structure
 └─ Inverse Conway Manoeuvre: design org around desired architecture

RIGHT SERVICE SIZE
 └─ Not lines of code
 └─ One Bounded Context = One service
 └─ Owned and deployable by one team
 └─ Rewritable in ~2 weeks by one team (Sam Newman)
```

---

*Next Phase → Inter-Service Communication (REST, OpenFeign, Sync vs Async)*
*Confirm when ready to proceed.*
