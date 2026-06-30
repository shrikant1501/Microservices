# Phase 5 — Service Discovery (Eureka)

> **80/20 Focus:** Why service discovery exists, how Eureka works, the 3 config lines that matter most, and the 5 interview questions every panel asks.

---

## 1. The Problem This Solves

After Phase 3, `todo-service` has this in `application.properties`:

```properties
user-service.url=http://localhost:8081
```

**This breaks in every real environment:**

```
Problem 1 — Multiple instances (scaling)
  user-service starts 3 instances for load:
    http://10.0.1.5:8081
    http://10.0.1.6:8081
    http://10.0.1.7:8081
  Which URL do you hardcode? You can't.

Problem 2 — Dynamic IPs (Docker / Kubernetes)
  Containers get a new IP every restart.
  Hardcoded IP is stale the moment the container restarts.

Problem 3 — Environment sprawl
  localhost:8081 → works on your laptop
  10.0.1.5:8081  → works in staging
  10.0.2.8:8081  → works in production
  Maintaining 3 configs for every service is a nightmare.
```

**Service Discovery** solves this: services register themselves by **name**, and callers look up by **name** — never hardcoded IP/port.

---

## 2. How Service Discovery Works

### The Pattern

```
STARTUP — each service registers itself:

  user-service starts → registers with Eureka:
    { name: "user-service", host: "10.0.1.5", port: 8081 }

  todo-service starts → registers with Eureka:
    { name: "todo-service", host: "10.0.1.6", port: 8082 }

AT CALL TIME — caller asks by name:

  todo-service needs to call user-service →
  Asks Eureka: "Where is user-service?" →
  Eureka: "10.0.1.5:8081, 10.0.1.7:8081, 10.0.1.9:8081" →
  Spring Cloud LoadBalancer picks one (round-robin) →
  Feign calls it
```

### Client-Side vs Server-Side Load Balancing

| | Client-Side (Eureka + Spring Cloud LB) | Server-Side (AWS ALB, Nginx) |
|---|---|---|
| **Who picks the instance?** | The calling service itself | A separate load balancer |
| **Discovery** | Each service has a registry client | DNS / external LB |
| **Resilience** | Registry cached locally — works even if Eureka is briefly down | Depends on LB health |
| **Used in** | Spring Cloud microservices | Production cloud deployments |

> **Interview answer:** "In Spring Cloud, we use client-side load balancing via Eureka + Spring Cloud LoadBalancer. Each service maintains a local cache of registered instances. When calling another service by name, Spring Cloud LoadBalancer selects an instance using round-robin by default. This cached registry means calls can still be made even if the Eureka server is briefly unavailable."

---

## 3. Eureka Internals — What Interviewers Ask

### Heartbeat & Lease

Every registered service sends a **heartbeat** to Eureka every **30 seconds** (default).
If Eureka doesn't receive a heartbeat for **90 seconds**, it removes the instance from the registry.

This means there is a **90-second window** where a crashed service may still appear as available in the registry. Callers will attempt to call it and fail — which is exactly why Phase 8 (Circuit Breaker) is needed.

### Self-Preservation Mode

> **Tricky interview Q:** "What is Eureka's self-preservation mode?"

If Eureka stops receiving heartbeats from a large percentage of services (e.g., a network partition), it assumes the *registry itself* has a network issue rather than all services dying. It enters **self-preservation mode** — stops evicting instances, even if heartbeats are missed.

- **Pro:** Prevents mass de-registration during a network blip
- **Con:** In actual outages, stale instances stay registered longer

**Production setting:** Disable in dev (`enable-self-preservation: false`), keep enabled in production.

### The Registry Is Eventually Consistent

Eureka replicates registry data across multiple Eureka server instances. Replication is **async** — there is a window where two Eureka servers have different views. This is intentional: Eureka is **AP** (Available + Partition-tolerant) in CAP theorem terms, not CP. It favours availability over strict consistency.

> **Interview Q:** "Is Eureka CP or AP?"
> "AP. Eureka favours availability over consistency. During a partition, each Eureka server continues serving its stale registry rather than going unavailable. This matches microservices philosophy — a slightly stale registry is better than no registry at all."

---

## 4. Architecture After Phase 5

```
BEFORE Phase 5:
  todo-service → http://localhost:8081/api/users/{id}   (hardcoded)

AFTER Phase 5:
  todo-service → http://user-service/api/users/{id}     (logical name)
                       │
                       └── Spring Cloud LB asks Eureka
                           Eureka returns: 10.0.1.5:8081
                           LB selects: 10.0.1.5:8081
                           Feign calls it

FULL PICTURE:

  ┌────────────────────────────────────────────────────────┐
  │              eureka-server  :8761                      │
  │  Registry:                                             │
  │    user-service  → [10.0.1.5:8081, 10.0.1.6:8081]    │
  │    todo-service  → [10.0.1.7:8082]                    │
  │    notif-service → [10.0.1.8:8083]                    │
  └────────────────────────────────────────────────────────┘
         ▲  register         ▲  register         ▲  register
         │  heartbeat        │  heartbeat        │  heartbeat
  ┌──────┴──────┐    ┌───────┴──────┐    ┌───────┴──────┐
  │ user-service│    │ todo-service │    │ notif-service│
  │   :8081     │◄───│   :8082      │    │   :8083      │
  └─────────────┘    └──────────────┘    └──────────────┘
                   Feign: "user-service"
                   LB resolves via Eureka
```

---

## 5. What Changes in Code

Three things change, total:

1. **New `eureka-server` Spring Boot app** — 1 dependency + 1 annotation + 1 property
2. **Each service adds `spring-cloud-starter-netflix-eureka-client`** — registers automatically
3. **`todo-service` removes the hardcoded URL** — Feign resolves by name via Eureka

That's it. The Feign client interface (`UserServiceClient`) does not change at all.

---

## 6. Eureka vs Alternatives

| | Eureka | Consul | Kubernetes Service Discovery |
|---|---|---|---|
| **Type** | Client-side, pull-based | Client-side + DNS | DNS + kube-proxy |
| **Health check** | Heartbeat | Active HTTP/TCP check | Liveness/readiness probes |
| **Use when** | Spring Cloud on VMs | Multi-DC, non-Java services | Running on Kubernetes |
| **In K8s needed?** | No — K8s has built-in DNS | No | Yes, native |

> **Interview Q:** "Would you use Eureka in a Kubernetes environment?"
> "No. Kubernetes has native service discovery built in — services communicate by their K8s service name (e.g., `http://user-service:8081`). K8s DNS + kube-proxy handles routing automatically. Adding Eureka on top is redundant. Eureka is the right choice for non-Kubernetes Spring Cloud deployments on VMs or bare-metal."

---

## 7. Top Interview Questions

**Q1. What is service discovery and why is it needed?**
> Services in a microservices system are deployed dynamically — multiple instances, changing IPs, container restarts. Hardcoding IP addresses is unmaintainable and breaks scaling. Service discovery allows services to register by name and callers to look up instances by name at runtime. Eureka is Spring Cloud's implementation — services register on startup, send heartbeats, and callers use the logical service name in Feign clients. Spring Cloud LoadBalancer resolves the name to a live IP:port.

**Q2. What happens if the Eureka server goes down?**
> Each Eureka client caches the registry locally. If the Eureka server goes down, clients continue using their last-known cached registry for some time. New registrations are not possible, and stale entries are not evicted — but existing service-to-service calls continue working using the cache. This is intentional resilience. For production, run **multiple Eureka server instances** in a cluster — each peer replicates its registry to the others.

**Q3. What is the difference between service registration and service discovery?**
> **Registration** is the act of a service announcing itself to the registry (name, host, port, metadata). It happens at startup. **Discovery** is the act of a caller asking the registry "who provides service X?" and getting back a list of instances. Eureka handles both. The client library performs registration automatically on Spring Boot startup via `@EnableEurekaClient` (or auto-configuration) and performs discovery when a `@FeignClient` with a service name resolves its URL.

**Q4. What is a heartbeat in Eureka?**
> A heartbeat is a periodic HTTP PUT request sent from a registered service to Eureka (default: every 30 seconds) to renew its lease. It signals "I'm still alive." If Eureka doesn't receive a heartbeat for 3× the interval (90 seconds default), it marks the instance as DOWN and eventually evicts it from the registry. This is how Eureka detects failed instances — passively, via missed heartbeats, not via active health checks.

**Q5. What is Ribbon? Is it still used?**
> Ribbon was Netflix's client-side load balancer, bundled with older Spring Cloud. It is now in **maintenance mode** and replaced by **Spring Cloud LoadBalancer** (since Spring Cloud 2020.0 / Hoxton). Spring Cloud LoadBalancer is lighter, doesn't depend on Netflix OSS, and integrates cleanly with Spring's reactive stack. In any new project, use Spring Cloud LoadBalancer — Ribbon is legacy.

---

## 8. Quick Revision Cheat Sheet

```
SERVICE DISCOVERY — WHY
 └─ Dynamic IPs, multiple instances, container restarts
 └─ Register by name → discover by name → never hardcode IPs

EUREKA MECHANICS
 └─ Service registers on startup (name, host, port)
 └─ Heartbeat every 30s → lease renewal
 └─ No heartbeat for 90s → evicted from registry
 └─ Client caches registry locally → resilient if Eureka is down
 └─ Self-preservation: mass heartbeat loss → stop evictions

EUREKA CAP: AP (Available + Partition-tolerant)
 └─ Stale registry is better than no registry

CLIENT-SIDE LOAD BALANCING
 └─ Caller resolves service name → gets list of instances
 └─ Spring Cloud LoadBalancer picks one (round-robin)
 └─ Old: Ribbon (deprecated). New: Spring Cloud LoadBalancer

EUREKA vs K8S
 └─ On K8s: use native DNS, don't use Eureka
 └─ On VMs/bare-metal: Eureka is the right choice

3 LINES THAT MATTER IN CONFIG
 └─ eureka.client.service-url.defaultZone=http://localhost:8761/eureka
 └─ spring.application.name=user-service   (this IS your service name)
 └─ @EnableEurekaServer  (on the server only)
```
