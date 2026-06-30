# Phase 1 — Why Microservices Exist

> **Role:** Senior Software Architect | Java Backend Engineer | Technical Interviewer
> **Goal:** Understand the fundamental problem that microservices solve — before writing a single line of code.

---

## 1. Goal of This Phase

By the end of Phase 1 you will:

- Understand **why software architecture evolved** from monoliths to microservices
- Know the **real problems** monolithic applications face at scale
- Understand **what microservices actually are** (not a buzzword definition)
- Know the **genuine trade-offs** — microservices are not always the right choice
- Be able to **defend an architectural decision** in an interview or design discussion
- Have a working **Spring Boot Todo Monolith** as the starting point for our evolution

---

## 2. Business Problem We Are Solving

Imagine you work at a mid-size e-commerce company. The application started as a single Spring Boot project. It handled:

- User registration and login
- Product catalogue
- Order management
- Payment processing
- Notification emails / SMS

**In 2019:** 5 engineers, 10,000 users/day — everything is fine.

**In 2023:** 50 engineers, 2,000,000 users/day — everything is on fire.

**What went wrong?**

1. A bug in the Notifications module crashes the entire application — including Payment.
2. The Payment team wants to deploy a hotfix but must wait for the Catalogue team to finish testing.
3. Scaling the application means running 10 copies of everything — even the modules that need no scaling.
4. The database has 300 tables. A junior developer added a column and broke 12 unrelated queries.
5. Onboarding a new engineer takes 3 months because the codebase is 500,000 lines.

**This is the monolith problem.** Microservices exist as an architectural response to these specific pain points.

---

## 3. Concepts to Learn

### 3.1 What is a Monolithic Architecture?

A **monolith** is an application where all business modules are built, deployed, and run as **a single unit**.

```
┌─────────────────────────────────────────────────┐
│              Single Deployable Unit             │
│                                                 │
│  ┌──────────┐  ┌───────────┐  ┌─────────────┐  │
│  │  Users   │  │  Orders   │  │  Payments   │  │
│  └──────────┘  └───────────┘  └─────────────┘  │
│  ┌──────────┐  ┌───────────┐                    │
│  │ Products │  │  Notify   │                    │
│  └──────────┘  └───────────┘                    │
│                                                 │
│         Single Shared Database                  │
│  ┌─────────────────────────────────────────┐    │
│  │           PostgreSQL / MySQL            │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

There are **three types** of monoliths:

| Type | Description | Example |
|------|-------------|---------|
| **Modular Monolith** | Single deployment, but code is organized into modules with clear boundaries | Well-structured Spring Boot app |
| **Layered Monolith** | Code organized by technical layer (controller/service/repo) not by business domain | Classic Spring MVC app |
| **Big Ball of Mud** | No structure at all — spaghetti code | Legacy enterprise apps |

> **Interview Insight:** A Modular Monolith is NOT a bad thing. Netflix, Shopify, and Stack Overflow ran successful modular monoliths for years.

---

### 3.2 The Real Problems with Monoliths at Scale

#### Problem 1 — Deployment Coupling

Every release must deploy the **entire application**. A one-line fix in Notifications requires full regression testing and deployment of the Payments module.

```
Developer A fixes Notifications
     ↓
Must wait for Developer B to finish Orders feature
     ↓
Must wait for QA to test ALL modules
     ↓
Full deployment of EVERYTHING
     ↓
If deployment fails, ALL features go down
```

#### Problem 2 — Scaling Inflexibility

You can only scale the **entire application**, not individual bottlenecks.

```
Reality:                     Monolith Scaling:
Payments = HIGH load         ┌──────────────────────┐
Notifications = LOW load     │ Entire App × 5 copies │
                             │ (including Notify×5)  │
                             │ = 5× wasted resources │
                             └──────────────────────┘
```

#### Problem 3 — Technology Lock-in

Every team must use the same language, framework, and version. The Payments team cannot adopt a specialized library without affecting every other module.

#### Problem 4 — Team Coupling (Conway's Law)

> "Organizations which design systems are constrained to produce designs which are copies of the communication structures of those organizations." — Melvin Conway (1967)

In a monolith, 50 engineers work on the same codebase. Every PR affects everyone. Merge conflicts. Code ownership disputes. Coordination overhead explodes.

#### Problem 5 — Reliability Risk

One OutOfMemoryError in the Notification service brings down Payments. In a financial system, this is catastrophic.

#### Problem 6 — Cognitive Load

500,000 lines of code in one project. A new engineer cannot understand the system. Onboarding takes months.

---

### 3.3 What is a Microservices Architecture?

**Microservices** is an architectural style where an application is built as a **collection of small, independently deployable services**, each:

- Owning its **own business capability**
- Owning its **own database**
- **Communicating over the network** (HTTP/REST, gRPC, or messaging)
- **Deployable independently**
- **Scalable independently**

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  User       │    │  Order      │    │  Payment    │
│  Service    │    │  Service    │    │  Service    │
│  ┌───────┐  │    │  ┌───────┐  │    │  ┌───────┐  │
│  │  DB   │  │    │  │  DB   │  │    │  │  DB   │  │
│  └───────┘  │    │  └───────┘  │    │  └───────┘  │
└─────────────┘    └─────────────┘    └─────────────┘
       │                  │                  │
       └──────────────────┼──────────────────┘
                     API Gateway
                          │
                       Clients
```

---

### 3.4 The Core Principles of Microservices

| Principle | Meaning |
|-----------|---------|
| **Single Responsibility** | Each service does one thing and does it well |
| **Loose Coupling** | Services are independent; a change in one does not force changes in others |
| **High Cohesion** | Related logic lives together in the same service |
| **Failure Isolation** | A crashed service does not crash others |
| **Independent Deployability** | Deploy, scale, and version services independently |
| **Decentralized Data Management** | Each service owns its own database |
| **Designed for Failure** | Assume the network will fail; build resilience in |

---

### 3.5 Monolith vs Microservices — Honest Comparison

| Dimension | Monolith | Microservices |
|-----------|----------|---------------|
| **Deployment** | Single unit | Independent per service |
| **Scalability** | Scale all-or-nothing | Scale individual services |
| **Team autonomy** | Low — shared codebase | High — team owns a service |
| **Technology flexibility** | Low — shared stack | High — polyglot possible |
| **Operational complexity** | Low | High |
| **Network overhead** | None (in-process) | Yes — all calls over the network |
| **Data consistency** | Easy (single DB, ACID) | Hard (distributed, eventual) |
| **Debugging / Tracing** | Easy (single log) | Hard (distributed tracing needed) |
| **Testing** | Easier | Harder (contract testing, integration) |
| **Good for** | Small teams, early stage products | Large teams, complex domains at scale |

---

### 3.6 When NOT to Use Microservices

> This is one of the most important things a senior architect says that juniors miss.

**Do NOT use microservices when:**

- Your team has fewer than 8–10 engineers
- Your domain is not yet well-understood (you will cut services at the wrong boundaries)
- You are building an MVP (time-to-market is more important than scalability)
- You do not have mature DevOps / CI-CD pipelines
- You do not have distributed tracing and centralized logging infrastructure

**Martin Fowler's rule:** *"Don't start with microservices. Start with a monolith, understand your domain boundaries, then extract services."*

---

## 4. Internal Working — How a Monolith Call Works vs a Microservices Call

### Monolith (In-Process Call)
```
HTTP Request
     ↓
OrderController.createOrder()
     ↓  [in-memory method call — nanoseconds]
PaymentService.processPayment()
     ↓  [in-memory method call]
NotificationService.sendEmail()
     ↓
HTTP Response
```
- No serialization, no network, no failure point between modules.
- ACID transaction spans the entire flow.

### Microservices (Network Call)
```
HTTP Request
     ↓
API Gateway
     ↓  [HTTP/REST call — milliseconds, can fail]
Order Service
     ↓  [HTTP/REST or Message Queue call]
Payment Service
     ↓  [HTTP/REST or Message Queue call]
Notification Service
     ↓
HTTP Response (or Async acknowledgement)
```
- Every arrow is a **network hop** — latency + failure risk.
- No ACID across services — requires **distributed transaction patterns** (Saga, CQRS).
- Requires **service discovery**, **circuit breakers**, **distributed tracing**.

> **This is why you learn ALL the other topics in the roadmap** — they all exist to address the complexity introduced by this network boundary.

---

## 5. Architecture Decision: The Extraction Decision Framework

Before extracting a service from a monolith, ask these questions:

1. **Does this capability have a different scaling requirement?** (Yes → extract)
2. **Does this capability have a different deployment cadence?** (Yes → extract)
3. **Does this capability have a clear, stable business boundary?** (No → do NOT extract yet)
4. **Does this capability need a different technology stack?** (Yes → extract)
5. **Does a separate team own this capability?** (Yes → extract)
6. **Is this capability a regulatory/compliance boundary?** (Yes → extract)

---

## 6. Step-by-Step Implementation — Phase 1 Starting Point

We begin with a **simple Spring Boot Todo Monolith**. This is intentionally simple. As we progress, we will identify the problems with this structure and extract services.

### Project Structure
```
todo-monolith/
├── src/main/java/com/microlearning/todo/
│   ├── TodoApplication.java
│   ├── user/
│   │   ├── User.java
│   │   ├── UserController.java
│   │   ├── UserService.java
│   │   └── UserRepository.java
│   ├── todo/
│   │   ├── Todo.java
│   │   ├── TodoController.java
│   │   ├── TodoService.java
│   │   └── TodoRepository.java
│   └── notification/
│       ├── NotificationService.java
│       └── (simulated email logic)
├── src/main/resources/
│   └── application.properties
└── pom.xml
```

The User, Todo, and Notification modules all live in a **single Spring Boot project**, share a **single H2 database**, and are deployed together as **one JAR**.

This is our Phase 1 monolith. In later phases, we will extract these into three separate microservices.

---

## 7. Real-World Use Cases

| Company | Journey |
|---------|---------|
| **Amazon** | Originally a monolith. Decomposed into ~2-tier services starting 2001. Now thousands of microservices. |
| **Netflix** | Moved from DVD monolith to microservices 2009–2011 after a major outage. Invented many resilience patterns. |
| **Uber** | Started as a monolith, extracted services as city count and team size grew. |
| **Shopify** | Runs a **modular monolith** successfully. Chose NOT to fully decompose because their domain is well-understood and team structure doesn't warrant it. |

---

## 8. Trade-offs and Alternative Approaches

### Alternative: Modular Monolith
- Internal modules with strict API boundaries
- Single deployment but logical separation
- Much simpler operationally
- **Best for:** teams < 20, well-understood domains

### Alternative: Service-Oriented Architecture (SOA)
- Predecessor to microservices
- Services communicate via Enterprise Service Bus (ESB)
- Heavy XML/SOAP, centralized governance
- **Problem:** ESB becomes a bottleneck and single point of failure

### Microservices vs SOA

| | SOA | Microservices |
|---|-----|---------------|
| Communication | ESB (heavy, centralized) | Direct HTTP / lightweight messaging |
| Data | Shared DB common | Database per service |
| Size | Large services | Small, focused services |
| Governance | Centralized | Decentralized |

---

## 9. Common Mistakes

| Mistake | Consequence |
|---------|-------------|
| Splitting microservices by technical layer (UserController service, UserRepository service) | Creates chatty, tightly-coupled services — worse than a monolith |
| Too many micro-services too early | Operational nightmare before you understand the domain |
| Sharing a database between services | Destroys independence — couples services at the data layer |
| Not investing in observability | You cannot debug distributed systems without tracing and centralized logs |
| Ignoring network failure in service calls | Cascading failures — one slow service brings down everything |

---

## 10. Production Best Practices

1. **Start with a modular monolith** — understand domain boundaries first.
2. **Extract services at natural domain boundaries** — not arbitrary technical lines.
3. **Invest in CI/CD pipelines** before extracting services — independent deployment is worthless without automation.
4. **Build observability first** — you cannot operate what you cannot see.
5. **Each service should be deployable by its own team** — if two teams must coordinate to deploy, your boundary is wrong.
6. **Document your Service Catalogue** — what services exist, who owns them, what APIs they expose.

---

## 11. Frequently Asked Interview Questions

**Q1. What is the difference between a monolith and microservices?**

> A monolith is a single deployable unit containing all business logic, sharing one database. Microservices break that into small, independently deployable services, each owning its data and deployed autonomously. The key differences are around deployment independence, scaling granularity, and failure isolation.

**Q2. Why were microservices introduced?**

> As systems scaled, monoliths created bottlenecks: you couldn't deploy part of the system, you couldn't scale individual capabilities, team coordination became expensive (Conway's Law), and one module's failure could take down everything. Microservices solve these by creating deployment, scaling, and failure isolation boundaries.

**Q3. What are the disadvantages of microservices?**

> Operational complexity (dozens of services to deploy and monitor), distributed system problems (network failures, latency, partial failures), data consistency challenges (no ACID across services), need for distributed tracing, service discovery, API gateways, and significantly more infrastructure.

**Q4. When would you choose a monolith over microservices?**

> For small teams, early-stage products where the domain isn't understood yet, MVPs where speed-to-market matters, or when you lack mature DevOps infrastructure. Microservices add significant operational overhead that only pays off at scale.

**Q5. What is Conway's Law and how does it relate to microservices?**

> Conway's Law states that a system's architecture tends to mirror the communication structure of the organization building it. In microservices, this is used deliberately — teams are structured around business capabilities, and each team owns a service that maps to that capability. This is called the "Inverse Conway Manoeuvre."

---

## 12. Tricky Interview Questions

**Q. Can two microservices share the same database?**

> Technically yes, but it violates the core principle of service autonomy. If Service A and Service B share a table, changing that table's schema requires coordinating both teams and testing both services simultaneously — you've recreated deployment coupling at the data layer. The answer in production should be: no shared database, no shared tables. If data sharing is needed, it should happen through APIs or events.

**Q. Are microservices always better than monoliths?**

> No. This is a classic trap question. Microservices trade deployment/scaling flexibility for enormous operational complexity. Shopify is a billion-dollar company running a modular monolith. The right answer depends on team size, domain maturity, and operational capability. Many organizations practice "premature decomposition" and create microservices debt.

**Q. What is the difference between microservices and SOA?**

> SOA uses a centralized Enterprise Service Bus (ESB) for communication with heavy SOAP/XML contracts, shared databases are common, and governance is centralized. Microservices use lightweight direct communication (REST/gRPC/messaging), each service owns its database, and governance is decentralized. Microservices can be seen as a refined, lightweight evolution of SOA.

**Q. What happens if a microservice is down?**

> This is the core reliability question. In a well-designed system: other services should continue functioning (failure isolation). The caller should implement a Circuit Breaker to stop hammering the failing service. Downstream consumers may get degraded responses (e.g., cached data or a fallback). In a poorly designed system, one failing service creates a cascading failure through the entire system.

---

## 13. Scenario-Based Interview Questions

**Scenario 1:** You are joining a startup with 8 engineers building a new product. The CTO wants microservices from day one. What do you advise?

> I would recommend starting with a well-structured modular monolith. With 8 engineers, the coordination overhead of managing 10+ services, writing CI/CD pipelines for each, setting up service discovery, distributed tracing, and API gateways will slow development massively. The domain isn't fully understood yet either — extracting services at wrong boundaries creates "distributed monolith" which is the worst of both worlds. Build a modular monolith, prove product-market fit, then extract services where there are genuine scaling or team autonomy needs.

**Scenario 2:** Your monolith has grown to 600,000 lines of code with 40 engineers. Deployments take 4 hours and break frequently. How do you approach decomposition?

> I would: (1) Map the domain using DDD Event Storming to identify bounded contexts. (2) Start with the "strangler fig" pattern — incrementally extract services at the identified boundaries rather than a big-bang rewrite. (3) Extract the highest-pain points first — the modules with the highest deployment frequency or scaling need. (4) Invest in CI/CD, observability, and service discovery infrastructure before the first extraction. (5) Never share databases between extracted services.

---

## 14. Small Exercise

Before moving to Phase 2, complete this exercise:

1. Look at the `todo-monolith` project structure.
2. Identify **three problems** you would face if this app grew to 50 engineers and 1 million users/day.
3. Identify **which modules** might be extracted into services first and **why** (what is the architectural reason for each extraction).
4. Write a one-paragraph answer to: *"Why would you NOT extract the User module and the Todo module into separate services immediately?"*

---

## 15. Quick Revision Cheat Sheet

```
MONOLITH
 └─ Single deployable unit
 └─ Shared database
 └─ In-process communication
 └─ Problems: deployment coupling, scaling inflexibility,
              team coupling, technology lock-in, reliability risk

MICROSERVICES
 └─ Independent deployable services
 └─ Database per service
 └─ Network communication (REST / Messaging)
 └─ Solves: independent deployment, granular scaling,
            team autonomy, failure isolation
 └─ Introduces: distributed systems complexity,
                eventual consistency, operational overhead

CORE PRINCIPLES
 └─ Single Responsibility
 └─ Loose Coupling
 └─ High Cohesion
 └─ Failure Isolation
 └─ Independent Deployability
 └─ Decentralized Data Management

WHEN TO USE MONOLITH: Small team, new domain, MVP
WHEN TO USE MICROSERVICES: Large team, understood domain, scale needed

ALTERNATIVES
 └─ Modular Monolith (best of both for mid-size teams)
 └─ SOA (predecessor — heavier, centralized ESB)

CONWAY'S LAW
 └─ System architecture mirrors org communication structure
 └─ Inverse Conway Manoeuvre: design org around desired architecture
```

---

*Next Phase → Service Decomposition & Domain-Driven Design*
*Confirm when ready to proceed.*
