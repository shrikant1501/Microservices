# Phase 3 — Inter-Service Communication

> **Role:** Senior Software Architect | Java Backend Engineer | Technical Interviewer
> **Prerequisite:** Phase 2 complete — you have identified 3 Bounded Contexts.
> **Goal:** Break the monolith into 3 independent Spring Boot services and wire them together with real HTTP communication.

---

## 1. Goal of This Phase

By the end of Phase 3 you will:

- Understand **why inter-service communication is the hardest part** of microservices
- Know the difference between **Synchronous** and **Asynchronous** communication
- Know **REST vs gRPC vs Messaging** and when to choose each
- Understand **OpenFeign** — what it is, how it works internally, and why it exists
- Build and run **3 independent Spring Boot services** talking to each other over HTTP
- Understand **what goes wrong** when a called service is down
- Know why this is the motivation for Phase 8 (Resilience4j)

---

## 2. Business Problem We Are Solving

After Phase 2, we know our three services. Now we face the first real distributed systems problem:

> **todo-service** needs to know if a user exists before creating a todo.
> In the monolith, it called `userRepository.findById()` — zero network cost, no failure possible.
> Now `user-service` runs on a different port, different JVM, possibly a different machine.

The question is: *how does one service talk to another service?*

And the follow-up question every architect must answer: *what happens when the service you are calling is down?*

---

## 3. Concepts to Learn

### 3.1 Synchronous vs Asynchronous Communication

This is the most fundamental communication choice in microservices.

```
SYNCHRONOUS COMMUNICATION:
  Caller sends request → waits → receives response
  
  todo-service ──── HTTP GET /users/7 ────► user-service
       ▲                                         │
       └────────── Response: {id:7, name:Alice} ─┘
  
  Caller is BLOCKED until response arrives.
  If user-service is down: todo-service's request FAILS immediately.

ASYNCHRONOUS COMMUNICATION:
  Caller sends message → does NOT wait → continues
  
  todo-service ──── UserCreated event ────► [ Kafka ] ──► notification-service
       │
       └── continues immediately, doesn't wait for notification

  Caller is NOT blocked. The message is delivered eventually.
  If notification-service is down: message waits in Kafka until it recovers.
```

| Dimension | Synchronous | Asynchronous |
|-----------|-------------|--------------|
| **Coupling** | Temporal — caller waits, both must be up | Loose — caller fires and forgets |
| **Latency** | Added per hop | No added latency to caller |
| **Use when** | You need an immediate answer | You don't need an immediate answer |
| **Failure model** | Cascading — one down service fails the caller | Isolated — consumer can be down, message waits |
| **Complexity** | Lower | Higher (message broker, idempotency, ordering) |
| **Examples** | REST, gRPC | Kafka, RabbitMQ, AWS SQS |

**Rule of thumb:**
- Use **synchronous** when the caller needs the result to continue (e.g., validate user before creating todo)
- Use **asynchronous** when the caller does not need a result immediately (e.g., send welcome email after creating user)

---

### 3.2 REST — The Standard for Synchronous Service Communication

**REST (Representational State Transfer)** over HTTP is the most common choice for synchronous inter-service communication in Java microservices.

Why REST dominates:
- Every language and framework supports it
- Human-readable (JSON)
- Easy to debug (curl, Postman)
- Stateless
- Cacheable

#### HTTP Client Options in Spring Boot

| Option | When to use | Notes |
|--------|-------------|-------|
| **RestTemplate** | Legacy (pre-Spring 5) | Deprecated in Spring 6, being removed |
| **WebClient** | Reactive/non-blocking calls | Best for high-throughput, reactive stacks |
| **RestClient** | Spring 6+ synchronous | New fluent API, replaces RestTemplate |
| **OpenFeign** | Declarative HTTP client | Best for microservices — simplest code |

> **Interview Insight:** "We use OpenFeign because it lets you define a REST client as an interface — the same interface-based abstraction you already use for repositories. It removes boilerplate, integrates with Eureka for service discovery, and integrates with Resilience4j for circuit breakers."

---

### 3.3 OpenFeign — Deep Dive

**OpenFeign** (originally Netflix Feign, now Spring Cloud OpenFeign) is a **declarative HTTP client** — you define what to call, not how.

#### Without OpenFeign (manual RestClient):
```java
// Every call requires: URL building, HTTP method, body serialization,
// response deserialization, error handling — repeated everywhere
UserResponse user = restClient
    .get()
    .uri("http://user-service/api/users/" + userId)
    .retrieve()
    .body(UserResponse.class);
```

#### With OpenFeign:
```java
@FeignClient(name = "user-service")
public interface UserServiceClient {
    @GetMapping("/api/users/{id}")
    UserResponse getUserById(@PathVariable Long id);
}

// Usage — exactly like calling a local service
UserResponse user = userServiceClient.getUserById(userId);
```

You write **zero** HTTP boilerplate. Feign handles:
- Building the HTTP request
- Serializing/deserializing JSON
- Integrating with Eureka for URL resolution (Phase 5)
- Integrating with Resilience4j for circuit breaking (Phase 8)
- Load balancing across multiple instances

#### How OpenFeign Works Internally

```
@FeignClient annotation
        │
        ▼
Spring creates a JDK Dynamic Proxy for the interface at startup
        │
        ▼
When you call userServiceClient.getUserById(7):
        │
        ▼
Proxy intercepts the call
        │
        ▼
Feign inspects the @GetMapping annotation: "GET /api/users/{id}"
Feign substitutes @PathVariable: "GET /api/users/7"
        │
        ▼
Feign resolves the base URL:
  Phase 3: hardcoded (http://localhost:8081)
  Phase 5: Eureka lookup (http://user-service → resolves to actual IP:port)
        │
        ▼
Feign sends the HTTP request via underlying HTTP client
(default: JDK HttpURLConnection, configurable to OkHttp/Apache HC)
        │
        ▼
Response arrives → Feign deserializes JSON → returns UserResponse object
```

#### OpenFeign Error Handling

By default, if the called service returns:
- **4xx** → Feign throws `FeignClientException` 
- **5xx** → Feign throws `FeignServerException`
- **Connection refused / timeout** → Feign throws `IOException`

You can customize this with a `FeignErrorDecoder` or let Resilience4j handle retries (Phase 8).

---

### 3.4 What Happens When a Service Is Down?

This is the core distributed systems problem that motivates everything in Phase 8.

```
SCENARIO: user-service is down.
          todo-service calls GET /api/users/7.
          
Without any resilience:
  todo-service: sends HTTP request
  [connection refused / timeout after 30 seconds]
  todo-service: throws exception
  API Gateway: returns 500 to client
  
ADDITIONAL PROBLEM — Thread starvation:
  todo-service has 200 HTTP threads.
  user-service is slow (not down, just slow — 60s response time).
  200 requests arrive at todo-service, each calls user-service.
  All 200 threads are blocked waiting for user-service.
  todo-service runs out of threads.
  todo-service is now ALSO unresponsive — even for requests that don't touch user-service.
  
  This is a CASCADING FAILURE.
```

This is why Phase 8 (Resilience4j) introduces:
- **Circuit Breaker** — stop calling the failing service
- **Timeout** — don't wait forever
- **Retry** — try again after a pause
- **Bulkhead** — limit concurrent calls to prevent thread starvation

For Phase 3, we accept the raw failure so you can SEE it. In Phase 8, we fix it.

---

### 3.5 gRPC — The Alternative

**gRPC** uses Protocol Buffers (binary serialization) over HTTP/2.

| | REST/JSON | gRPC |
|---|-----------|------|
| **Protocol** | HTTP/1.1 | HTTP/2 |
| **Serialization** | JSON (text) | Protobuf (binary) |
| **Performance** | Slower (text parsing) | ~7x faster |
| **Type safety** | None (JSON is untyped) | Strong (generated types) |
| **Browser support** | Native | Requires grpc-web proxy |
| **Debugging** | Easy (curl, Postman) | Harder (binary format) |
| **Spring support** | Native (spring-web) | Spring gRPC (newer) |
| **Use when** | External APIs, mixed language teams | Internal high-throughput service-to-service |

> **Interview Insight:** "I'd use REST for external APIs and gRPC for high-throughput internal service-to-service communication where latency matters — for example, an ML inference service called hundreds of times per second."

---

## 4. Architecture After Phase 3

```
                         ┌──────────────────────┐
                         │   Client (Postman)   │
                         └──────────┬───────────┘
                                    │
              ┌─────────────────────┼──────────────────────┐
              ▼                     ▼                       ▼
 ┌─────────────────────┐ ┌─────────────────────┐ ┌──────────────────────┐
 │   user-service      │ │   todo-service       │ │ notification-service │
 │   :8081             │ │   :8082              │ │   :8083              │
 │                     │ │                      │ │                      │
 │  UserController     │ │  TodoController      │ │  (stub — Phase 4)    │
 │  UserService        │◄│  TodoService         │ │                      │
 │  UserRepository     │ │  UserServiceClient   │ │                      │
 │                     │ │  (Feign)             │ │                      │
 │  ┌──────────────┐   │ │  ┌──────────────┐   │ │  ┌─────────────────┐ │
 │  │  H2 (users)  │   │ │  │  H2 (todos)  │   │ │  │  (no DB yet)    │ │
 │  └──────────────┘   │ │  └──────────────┘   │ │  └─────────────────┘ │
 └─────────────────────┘ └─────────────────────┘ └──────────────────────┘
            ▲                      │
            └──── Feign HTTP ───────┘
              GET /api/users/{id}
```

---

## 5. Implementation Plan

We create 3 new Spring Boot projects. The monolith remains untouched as our reference.

```
Microservices/
├── todo-monolith/          ← Phase 1 reference (untouched)
├── user-service/           ← NEW: Phase 3
├── todo-service/           ← NEW: Phase 3 (calls user-service via Feign)
└── notification-service/   ← NEW: Phase 3 stub (full impl in Phase 4)
```

---

## 6. Real-World Use Cases

| Company | Inter-Service Communication |
|---------|---------------------------|
| **Netflix** | REST internally via Feign + Ribbon (predecessor to Spring Cloud LoadBalancer). Now migrating to gRPC for performance-critical paths |
| **Uber** | Uses gRPC for internal service-to-service, REST for external APIs |
| **Spotify** | REST over HTTP/2 internally |
| **Amazon** | Mix of REST and async SQS/SNS for decoupled flows |

---

## 7. Trade-offs

| Approach | When to use | Risk |
|----------|------------|------|
| **OpenFeign (REST)** | Default for synchronous calls | Tight temporal coupling |
| **RestClient/WebClient** | Fine-grained control needed | More boilerplate |
| **gRPC** | High-throughput, type-safe internal calls | More setup, less readable |
| **Messaging (Kafka)** | Fire-and-forget, async flows | More infrastructure, eventual consistency |

---

## 8. Common Mistakes

| Mistake | Consequence |
|---------|-------------|
| Synchronous call for every inter-service interaction | Long synchronous chains — single failure cascades |
| No timeout configured on Feign client | Thread exhaustion on slow downstream services |
| Calling another service's database directly | Bypasses the service's business logic and ownership |
| Passing internal entity objects between services (not DTOs) | Couples service implementations at the type level |
| Feign client URL hardcoded to localhost | Breaks in any non-local deployment (Docker, K8s) |

---

## 9. Production Best Practices

1. **Always set timeouts** on Feign clients — never wait indefinitely.
2. **Use DTOs, not entities**, as the API contract between services.
3. **Version your APIs** — `GET /api/v1/users/{id}` — so you can evolve without breaking consumers.
4. **Treat the Feign interface as a contract** — put it in a shared module or document it clearly.
5. **Add circuit breakers before going to production** (Phase 8).
6. **Log every inter-service call** with a correlation ID (Phase 11).
7. **Health-check dependencies** — if user-service is required, todo-service's `/actuator/health` should reflect that.

---

## 10. Frequently Asked Interview Questions

**Q1. What is OpenFeign and why would you use it over RestTemplate?**

> OpenFeign is a declarative HTTP client from Spring Cloud. You define an interface annotated with Spring MVC annotations and Feign generates the implementation at runtime using a JDK dynamic proxy. Compared to RestTemplate, there is zero HTTP boilerplate — no URL building, no manual JSON handling, no response extraction. It also integrates natively with Eureka (automatic URL resolution from service name) and Resilience4j (circuit breaker wrapping). RestTemplate is deprecated in Spring 6; the modern alternatives are RestClient (imperative) or WebClient (reactive), but Feign remains the cleanest option for microservice-to-microservice calls.

**Q2. What happens internally when you call a Feign client method?**

> Spring creates a JDK dynamic proxy for the Feign interface at application startup. When you invoke a method on the proxy, the proxy intercepts the call, reads the Spring MVC annotations (@GetMapping, @PathVariable, etc.) to construct the HTTP request, resolves the base URL (from Eureka if configured, or from the url attribute), executes the HTTP call using the underlying HTTP client, deserializes the response JSON back to the return type, and returns the object. If the response is a 4xx/5xx, a FeignClientException is thrown, which can be caught or handled by a Resilience4j circuit breaker.

**Q3. When would you choose asynchronous over synchronous communication?**

> When the caller does not need an immediate result to continue its work. Key signals: (1) The operation is non-critical to the main flow (sending an email, updating a read-model, analytics). (2) The downstream service has high latency. (3) You want failure isolation — if the downstream service is down, you don't want the upstream to fail. (4) The operation needs to be retried reliably. Async communication decouples services temporally — both don't need to be up at the same time. The trade-off is eventual consistency and the complexity of a message broker.

**Q4. What is a cascading failure and how do you prevent it?**

> A cascading failure occurs when a failing service causes its callers to also fail, which causes their callers to fail, and so on — a chain reaction across the system. It happens because callers block threads waiting for the failing service, exhaust their thread pools, and become unresponsive themselves. Prevention: (1) Circuit Breaker — stop calling the failing service after N failures; return a fallback immediately. (2) Timeouts — never wait forever; free the thread after a bounded time. (3) Bulkhead — limit the number of concurrent calls to a single downstream service so one bad dependency can't exhaust all threads. (4) Async communication — if you don't synchronously wait, you can't cascade.

---

## 11. Tricky Interview Questions

**Q. Two microservices need to call each other (A calls B, B calls A). How do you handle this?**

> Circular dependencies between services are an architectural smell — they indicate that the service boundary is wrong. Services A and B are too tightly coupled and likely belong in the same bounded context. The solution is to re-evaluate the domain boundaries: either merge A and B into one service, or introduce a third service C that both A and B call (removing the circularity). If the circular call is truly unavoidable (rare), use async messaging — A publishes an event, B consumes it, B publishes a result event, A consumes that — avoiding the synchronous cycle.

**Q. Should a Feign client interface be in the calling service or shared as a library?**

> This is a real architectural debate. Option A (in the calling service): the consumer owns the client, defines what it needs, no shared artifact. Preferred — follows the Consumer-Driven Contract principle. Option B (shared library published by the provider): the provider controls the client, guaranteed to match the API. Risk: all consumers are forced to upgrade when the provider changes — creates deployment coupling. Netflix initially used shared clients (Ribbon), later moved away from it. The correct production approach is Option A with contract testing (Pact) to verify the client matches the server's actual API.

---

## 12. Scenario-Based Questions

**Scenario:** Your `order-service` calls `inventory-service` to check stock before placing an order. `inventory-service` goes down. `order-service` starts timing out. Within 2 minutes, `order-service` has no available threads and is completely unresponsive. What failed, what should have been in place, and how do you fix it?

> What failed: No timeout, no circuit breaker, no bulkhead. All 200 threads of order-service are blocked waiting for inventory-service's 30-second default socket timeout. New incoming requests have no thread to serve them.
> What should be in place: (1) A short connection timeout (1–2s) and read timeout (3–5s) on the Feign client. (2) A Circuit Breaker — after 5 failures, open the circuit and return a fallback (e.g., "assume item is available, check asynchronously"). (3) A Bulkhead — cap concurrent calls to inventory-service at 20 threads, so the remaining 180 threads still handle other operations.
> How to fix now: Restart order-service (immediate). Deploy with Resilience4j config (permanent fix).

---

## 13. Quick Revision Cheat Sheet

```
SYNCHRONOUS COMMUNICATION
 └─ Caller waits for response
 └─ REST (JSON/HTTP), gRPC (Protobuf/HTTP2)
 └─ Use when: caller needs result to continue
 └─ Risk: temporal coupling, cascading failure

ASYNCHRONOUS COMMUNICATION
 └─ Caller fires and forgets
 └─ Kafka, RabbitMQ, AWS SQS
 └─ Use when: caller doesn't need immediate result
 └─ Benefit: temporal decoupling, failure isolation
 └─ Cost: eventual consistency, broker infrastructure

OPENFEIGN
 └─ Declarative HTTP client (interface + annotations)
 └─ Works via JDK dynamic proxy
 └─ Integrates with: Eureka (URL resolution), Resilience4j (circuit breaker)
 └─ Prefer over RestTemplate (deprecated) for service-to-service calls

CASCADING FAILURE
 └─ Slow downstream → caller threads exhausted → caller also down
 └─ Prevention: Circuit Breaker + Timeout + Bulkhead (Phase 8)

REST vs gRPC
 └─ REST: human-readable, universal support, external APIs
 └─ gRPC: binary, fast, type-safe, internal high-throughput

CROSS-SERVICE DATA (3 options)
 └─ A: Sync REST call (real-time, adds latency+coupling)
 └─ B: Denormalized snapshot (fast, eventually stale)
 └─ C: Event-driven sync (async, eventually consistent)
```

---

*Next Phase → Event-Driven Architecture & Kafka*
*Confirm when ready to proceed.*
