# Phase 6 — API Gateway (Spring Cloud Gateway)

> **80/20 Focus:** Why a gateway exists, the 4 core concepts (Route/Predicate/Filter/Chain), the config patterns interviewers probe, and what makes Gateway different from a load balancer.

---

## 1. The Problem This Solves

After Phase 5, clients talk to services directly:

```
Mobile App  → http://localhost:8081/api/users      (user-service)
Mobile App  → http://localhost:8082/api/todos      (todo-service)
Browser     → http://localhost:8081/api/users      (same, different client)
```

**Problems with direct client-to-service communication:**

| Problem | Impact |
|---------|--------|
| Client knows internal service topology | Adding/moving a service breaks every client |
| Every service must handle CORS, auth, rate limiting | Duplicated cross-cutting logic in every service |
| No single place to add logging/tracing headers | Inconsistent observability |
| N services × M clients = N×M coupling | Unmanageable at scale |
| Load balancing across service instances | Client must implement it |

**Solution: API Gateway** — one front door. All traffic enters here. Clients know ONE address.

```
BEFORE:         Client → user-service
                Client → todo-service
                Client → notification-service

AFTER:          Client → API Gateway → [routes to] → user-service
                                   → [routes to] → todo-service
                                   → [routes to] → notification-service
```

---

## 2. What an API Gateway Does

```
Incoming Request
      │
      ▼
┌─────────────────────────────────────────────────────┐
│                  API GATEWAY                        │
│                                                     │
│  1. PREDICATE   ─── does the request match?         │
│     Path=/api/users/** → yes, route to user-service │
│                                                     │
│  2. PRE-FILTERS ─── before routing                  │
│     • Add correlation ID header                     │
│     • Validate JWT token                            │
│     • Rate limit check                              │
│     • Log request                                   │
│                                                     │
│  3. ROUTE       ─── forward to target service       │
│     lb://user-service → Eureka lookup → 10.0.1.5   │
│                                                     │
│  4. POST-FILTERS ── after response returns          │
│     • Add CORS headers                              │
│     • Log response time                             │
│     • Mask sensitive fields                         │
└─────────────────────────────────────────────────────┘
      │
      ▼
 Response to Client
```

---

## 3. Core Concepts

### Route
The basic unit of the gateway config. **If predicate matches → apply filters → forward to URI.**

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service-route          # unique identifier
          uri: lb://user-service          # lb:// = load-balanced via Eureka
          predicates:
            - Path=/api/users/**          # match condition
          filters:
            - StripPrefix=0              # optional filter
```

### Predicate
Condition that must be true for the route to apply.

| Predicate | Example | Use |
|-----------|---------|-----|
| `Path` | `/api/users/**` | Most common — route by URL path |
| `Method` | `GET, POST` | Route by HTTP method |
| `Header` | `X-Version=2` | Route by request header (for versioning) |
| `Host` | `**.example.com` | Route by hostname |
| `Weight` | `weight=80` | Canary / A-B deployments |

### Filter
Action applied before (pre) or after (post) routing.

**Built-in filters:**

| Filter | What it does |
|--------|-------------|
| `AddRequestHeader` | Inject a header before forwarding |
| `AddResponseHeader` | Inject a header into the response |
| `RewritePath` | Transform the URL path |
| `CircuitBreaker` | Wrap route in a circuit breaker |
| `RateLimiter` | Token-bucket rate limiting per route |
| `StripPrefix` | Remove N path segments before forwarding |
| `Retry` | Retry on failure with configurable conditions |

### GlobalFilter
Applied to **every** route automatically. The right place for cross-cutting concerns:
- Add Correlation ID
- JWT validation
- Access logging

---

## 4. `lb://` — The Integration With Eureka

```yaml
uri: lb://user-service
```

`lb://` tells Spring Cloud Gateway: *"Resolve this service name via the load-balanced registry (Eureka), not as a literal URL."*

```
Request: GET /api/users/1
  → Gateway matches route: uri=lb://user-service
  → Spring Cloud LoadBalancer asks Eureka: "Where is user-service?"
  → Eureka returns: [10.0.1.5:8081, 10.0.1.6:8081]
  → LoadBalancer picks: 10.0.1.5:8081 (round-robin)
  → Gateway forwards: GET http://10.0.1.5:8081/api/users/1
```

Without `lb://`, you'd write `uri: http://localhost:8081` — which defeats the purpose.

---

## 5. Global Filter — Correlation ID Injection

This is the most impactful filter for observability. Every request flowing through the gateway gets a unique ID. That ID propagates to all downstream services. When debugging a request across 5 services, you search logs by that one ID.

```java
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst("X-Correlation-Id");

        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        // 1. Add to outgoing request to downstream service
        // 2. Add to response back to client (so client can reference it in support tickets)
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-Correlation-Id", correlationId))
                .response(r -> r.getHeaders().add("X-Correlation-Id", correlationId))
                .build();

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() { return -1; } // run first, before all other filters
}
```

---

## 6. Gateway vs Load Balancer vs Reverse Proxy

This question appears in **every senior interview.**

| | API Gateway | Load Balancer (ALB/Nginx) | Reverse Proxy |
|---|---|---|---|
| **Routing** | Path/Header/Method-based, dynamic | IP/Port-based, static rules | Path-based, static config |
| **Auth/JWT** | Yes — filter layer | No | No |
| **Rate limiting** | Yes — per route/user | Yes — per IP | Limited |
| **Service discovery** | Yes — `lb://` Eureka | No | No |
| **Response transforms** | Yes — filters | No | No |
| **Protocol** | HTTP/WebSocket | TCP/HTTP | HTTP |
| **Spring support** | Spring Cloud Gateway | External (AWS ALB) | External (Nginx) |

> **Interview answer:** "An API Gateway is an application-level component that understands HTTP. It can route by path/headers, apply auth, rate limiting, request/response transformation, and circuit breaking. A load balancer operates at the transport level — it distributes traffic by IP/port but doesn't inspect application-level details like JWT tokens or URL paths at the application logic level."

---

## 7. Architecture After Phase 6

```
                    ┌──────────────────────────┐
                    │   Clients (mobile/web)   │
                    └─────────────┬────────────┘
                                  │ single entry point
                    ┌─────────────▼────────────┐
                    │      api-gateway :8080   │
                    │                          │
                    │  /api/users/**  → lb://user-service
                    │  /api/todos/**  → lb://todo-service
                    │                          │
                    │  GlobalFilters:           │
                    │  • Correlation ID         │
                    │  • (JWT in Phase 10)      │
                    └────┬──────────┬──────────┘
                         │          │
               ┌─────────▼──┐  ┌───▼────────┐
               │user-service│  │todo-service│
               │   :8081    │  │   :8082    │
               └────────────┘  └────────────┘
                         both registered in Eureka :8761
```

---

## 8. Spring Cloud Gateway vs Zuul

| | Spring Cloud Gateway | Zuul (Netflix) |
|---|---|---|
| **Model** | Reactive (Netty, WebFlux) — non-blocking | Servlet-based — blocking threads |
| **Performance** | High throughput, fewer threads | Thread-per-request, limited concurrency |
| **Maintenance** | Active, Spring team | Zuul 1 deprecated; Zuul 2 not in Spring Cloud |
| **Use** | All new projects | Legacy projects only |

> **Interview Q:** "Why Spring Cloud Gateway over Zuul?"
> "Spring Cloud Gateway is built on Project Reactor and Netty — it's fully non-blocking and reactive. It handles high concurrency with a small thread pool. Zuul 1 is servlet-based and blocking — each connection holds a thread, which limits throughput. Netflix themselves moved to Zuul 2 (non-blocking) and later to Envoy. For Spring Boot projects, Spring Cloud Gateway is the current standard."

---

## 9. Key Interview Questions

**Q1. What is an API Gateway and what problems does it solve?**
> An API Gateway is the single entry point for all client requests in a microservices system. It solves: (1) client-to-service coupling — clients only know the gateway, not internal topology; (2) cross-cutting concerns duplication — auth, rate limiting, CORS, logging done once in the gateway instead of every service; (3) protocol translation — gateway can accept REST and forward as gRPC; (4) A/B deployments — route a percentage of traffic to a new version using weight predicates.

**Q2. What is the difference between a Route, Predicate, and Filter?**
> A **Route** is the complete forwarding rule — "if predicate matches, apply filters, forward to URI." A **Predicate** is the matching condition — "does this request match? (by path, header, method, host)." A **Filter** is an action applied before or after forwarding — add a header, validate a JWT, rate-limit, rewrite the path. Together they form: `if (predicate) → run filters → forward to uri`.

**Q3. How does the gateway integrate with Eureka?**
> The gateway is also a Eureka client — it registers with Eureka and fetches the registry. When a route uses `uri: lb://service-name`, the gateway uses Spring Cloud LoadBalancer (backed by the Eureka registry) to resolve the service name to a live IP:port. This means adding or removing service instances requires no gateway config changes — it automatically routes to whatever is registered.

**Q4. Where should JWT validation happen — gateway or each service?**
> Both, with different roles. The gateway validates the token's **signature and expiry** (coarse-grained) and extracts claims into headers (e.g., `X-User-Id`, `X-User-Role`) for downstream services. Individual services perform **authorization** — checking if the authenticated user has permission for the specific resource. This separation means services never see the raw JWT (simpler) but still make authorization decisions on the propagated claims. Phase 10 implements this.

**Q5. What is the BFF pattern?**
> Backend for Frontend — a specialised gateway or aggregation layer for a specific client type (mobile, web, IoT). Instead of a generic gateway, BFF composes responses tailored to the client. Mobile needs a compact, low-latency response; the web dashboard needs richer data. Each BFF calls the underlying microservices and assembles a client-optimised response. It's a pattern on top of the API Gateway, not a replacement.

---

## 10. Tricky Interview Questions

**Q. What happens if the API Gateway goes down?**
> The entire system becomes unreachable to external clients — the gateway is a single point of failure. Mitigation: (1) Run multiple gateway instances behind a hardware/cloud load balancer (AWS ALB, GCP LB). (2) The underlying services continue to work — internal service-to-service calls (which bypass the gateway) are unaffected. (3) Circuit breakers and retries in the gateway protect against downstream failures. The gateway itself must be stateless (no session state) so any instance can serve any request.

**Q. Should services be reachable without the gateway?**
> In production, no — services should only be accessible from within the internal network, not the public internet. Only the gateway's port is publicly exposed. Internal service-to-service calls go directly by service name (e.g., `lb://user-service`) — they don't go through the gateway, which would add unnecessary latency and a potential bottleneck. This means network-level access control (VPC, security groups, firewall rules) is essential.

---

## 11. Quick Revision Cheat Sheet

```
API GATEWAY — PURPOSE
 └─ Single entry point for all external traffic
 └─ Eliminates client-to-service coupling
 └─ Cross-cutting concerns in ONE place (auth, rate-limit, CORS, logging)

CORE CONCEPTS
 └─ Route:     if predicate → apply filters → forward to uri
 └─ Predicate: matching condition (Path, Header, Method, Host, Weight)
 └─ Filter:    action before/after forwarding (add header, JWT check, rate limit)
 └─ GlobalFilter: applied to EVERY route (correlation ID, auth)

KEY CONFIG
 └─ uri: lb://service-name → Eureka-resolved, load-balanced
 └─ predicates: Path=/api/users/** → match by path
 └─ filters: AddRequestHeader, RateLimiter, CircuitBreaker

GATEWAY vs LOAD BALANCER
 └─ Gateway: application-level, understands HTTP/headers/JWT/paths
 └─ Load Balancer: transport-level, distributes by IP/port

SPRING CLOUD GATEWAY vs ZUUL
 └─ Gateway: reactive (Netty/WebFlux), non-blocking, high throughput — USE THIS
 └─ Zuul 1:  blocking, thread-per-request — LEGACY, avoid

CORRELATION ID PATTERN
 └─ Gateway generates UUID if not present in incoming request
 └─ Injects as X-Correlation-Id header to all downstream services
 └─ Downstream services log it → single ID traces request across all services

SINGLE POINT OF FAILURE MITIGATION
 └─ Run multiple gateway instances behind a cloud LB (AWS ALB)
 └─ Gateway must be stateless (no session) — any instance handles any request
 └─ Internal service-to-service calls bypass the gateway (direct lb://)
```
