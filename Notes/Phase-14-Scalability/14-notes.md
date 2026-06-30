# Phase 14 — Scalability
## (Horizontal vs Vertical · Stateless Services · Load Balancing · Redis Caching · DB Replication · Sharding · Rate Limiting)

> **80/20 Focus:** Every senior interview asks "how do you scale this system?" Know the difference between horizontal and vertical, why stateless is mandatory for horizontal scaling, and when to use a cache vs a read replica.

---

## 1. The Problem

A single Todo service handles 100 requests/second fine. Black Friday arrives — 10,000 requests/second. What do you do?

```
Option A: Buy a bigger server (32 cores, 256GB RAM)    ← Vertical scaling
Option B: Run 100 copies of the same service           ← Horizontal scaling

Which is better? It depends. But microservices are designed for Option B.
```

---

## 2. Horizontal vs Vertical Scaling

```
VERTICAL SCALING (Scale Up)
─────────────────────────────────────────────────────
  Before: 1 server, 4 cores, 16GB RAM
  After:  1 server, 32 cores, 256GB RAM

  ✅ Simple — no code changes
  ✅ No distributed systems problems
  ❌ Hard ceiling — can't go beyond the biggest machine
  ❌ Single point of failure
  ❌ Expensive non-linearly (2x cores ≠ 2x cheaper)
  ❌ Requires downtime to upgrade

HORIZONTAL SCALING (Scale Out)
─────────────────────────────────────────────────────
  Before: 1 instance of todo-service
  After:  10 instances of todo-service behind a load balancer

  ✅ No ceiling — add instances on demand
  ✅ No single point of failure
  ✅ Commodity hardware (cheap)
  ✅ Zero-downtime scaling
  ❌ Requires stateless services
  ❌ Distributed systems complexity (consistency, coordination)
  ❌ Harder to debug (which instance handled this request?)
```

### The fundamental rule
**Microservices are designed for horizontal scaling.** This is the entire reason services must be stateless.

---

## 3. Stateless Services

### What "stateless" means

A service is **stateless** when any instance can handle any request, because no request-specific data is stored inside the JVM.

```
STATEFUL (wrong):
  Instance A handles login → stores session { userId: 42 } in memory
  Next request routed to Instance B → session not found → user logged out

STATELESS (correct):
  Instance A handles login → issues JWT
  Next request to Instance B → validates JWT → no memory needed
  Any instance can serve any request
```

### What to NEVER store in instance memory for horizontal scaling:
- HTTP sessions (use Redis session store instead)
- User authentication state (use JWT / token)
- Request counters for rate limiting (use Redis instead)
- Circuit breaker state shared across instances (use distributed state store)
- Shopping cart / wizard steps (use a database or Redis)

### Spring Boot stateless checklist
```java
// ✅ CORRECT — stateless
@RestController
public class TodoController {
    // All state comes from the DB or request headers
    // No instance fields that accumulate per-request state
}

// ❌ WRONG — stateful
@RestController
public class TodoController {
    private int requestCount = 0;  // NOT thread-safe. NOT shared across instances.
    // Instance A: requestCount=500, Instance B: requestCount=300
    // GET /metrics/requests → returns 500 or 300 depending on which instance answered
}
```

---

## 4. Load Balancing

### What a load balancer does

Distributes incoming requests across multiple service instances so no single instance is overwhelmed.

```
                    ┌─────────────────────────────┐
                    │       Load Balancer          │
                    │  (Round Robin / Least Conn)  │
                    └──────┬──────┬──────┬─────────┘
                           │      │      │
                    ┌──────▼─┐ ┌──▼────┐ ┌▼───────┐
                    │Instance│ │Instance│ │Instance│
                    │   A    │ │   B    │ │   C    │
                    │ :8082  │ │ :8083  │ │ :8084  │
                    └────────┘ └────────┘ └────────┘
```

### Load balancing algorithms

| Algorithm | How it works | Best for |
|---|---|---|
| **Round Robin** | Request 1→A, 2→B, 3→C, 4→A… | Homogeneous instances, similar request cost |
| **Least Connections** | Route to instance with fewest active requests | Variable request duration (some slow, some fast) |
| **Weighted Round Robin** | Instance A gets 50%, B gets 30%, C gets 20% | Mixed hardware (some instances more powerful) |
| **IP Hash** | Same client IP always goes to same instance | Stateful apps that can't be made stateless yet |
| **Random** | Random instance picked | Simple, surprisingly effective at scale |

### In our stack: Spring Cloud LoadBalancer

Eureka + Spring Cloud LoadBalancer = client-side load balancing.

```
In todo-service, Feign calls user-service:
  1. @FeignClient(name = "user-service") 
  2. Spring Cloud LoadBalancer asks Eureka: "who are the instances of user-service?"
  3. Eureka returns: [10.0.1.5:8081, 10.0.1.6:8081, 10.0.1.7:8081]
  4. LoadBalancer picks one (Round Robin by default)
  5. Feign sends request directly to that instance

This is CLIENT-SIDE load balancing — the client (todo-service) does the balancing,
not an intermediary proxy. No single load balancer bottleneck.
```

**Server-side vs Client-side load balancing:**

| | Server-side | Client-side |
|---|---|---|
| Who balances? | Dedicated proxy (Nginx, HAProxy, AWS ALB) | The calling service itself |
| Service awareness | None — just IPs | Full (via Eureka) |
| Single point of failure | Yes (mitigated by HA LB setup) | No |
| Examples | AWS ALB, Nginx, Istio | Spring Cloud LoadBalancer, Ribbon |

---

## 5. Redis Caching

### Why caching exists

```
WITHOUT CACHE:
  GET /api/users/42 → DB query → 15ms response
  Called 1000 times/second = 1000 DB queries/second → DB overwhelmed

WITH CACHE:
  First call:  GET /api/users/42 → DB miss → 15ms → store in Redis
  Next 999:    GET /api/users/42 → Redis hit → 1ms → DB sees 1 query/TTL
```

### Cache strategies

```
CACHE-ASIDE (Lazy Loading) — most common in microservices
─────────────────────────────────────────────────────────
  Read:
    1. Check cache → HIT → return cached value
    2. MISS → query DB → store result in cache → return
  Write:
    1. Write to DB
    2. INVALIDATE the cache key (or let it expire via TTL)

  ✅ Only caches what's actually read (no wasted memory)
  ✅ Cache failure doesn't break reads (falls back to DB)
  ❌ First request after cache miss is slow (cache cold start)
  ❌ Cache inconsistency window (data changed in DB but not yet in cache)

WRITE-THROUGH
─────────────────────────────────────────────────────────
  Write: update DB + update cache atomically
  Read:  always from cache (always warm)
  ❌ Two writes on every mutation (slower writes)
  ❌ Caches data that may never be read

READ-THROUGH
─────────────────────────────────────────────────────────
  Cache sits in front of DB. App talks only to cache.
  Cache handles DB reads automatically on miss.
  Used in: Redis with write-back mode, Hibernate 2nd level cache
```

### Spring Boot Cache with Redis

```java
// pom.xml
// spring-boot-starter-data-redis
// spring-boot-starter-cache

// application.properties
spring.cache.type=redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.cache.redis.time-to-live=300s   // 5 minute TTL

// UserService.java
@Service
public class UserService {

    @Cacheable(value = "users", key = "#id")
    // On first call: executes method, stores result in Redis with key "users::42"
    // On subsequent calls: returns cached value, method body SKIPPED
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    @CacheEvict(value = "users", key = "#id")
    // On update/delete: removes the cached entry, next read re-fetches from DB
    public UserResponse updateUser(Long id, UpdateUserRequest req) { ... }

    @CachePut(value = "users", key = "#result.id")
    // Executes method AND updates cache — used for creates/updates
    public UserResponse createUser(CreateUserRequest req) { ... }
}
```

### Cache invalidation — the hardest problem

> *"There are only two hard things in Computer Science: cache invalidation and naming things."* — Phil Karlton

```
STRATEGIES FOR INVALIDATION:

1. TTL (Time-To-Live)     — expire after N seconds. Simple. Accepts stale window.
2. Event-driven           — on UserUpdated Kafka event, evict cache entry.
                             Near real-time consistency across services.
3. Cache-aside invalidate — on every write, explicitly delete the cache key.
4. Versioned keys         — cache key includes version. "user:42:v3" vs "user:42:v4"

IN MICROSERVICES: Event-driven invalidation is the cleanest.
  user-service publishes UserUpdated event.
  todo-service consumes it → @CacheEvict on its local user cache.
  No polling. No TTL guessing.
```

### What NOT to cache
- Highly personalized data (every user sees different data → no cache benefit)
- Frequently changing data (invalidation cost > cache benefit)
- Security-sensitive data (authorization checks must be fresh)
- Write-heavy paths (cache overhead without read benefit)

---

## 6. Database Replication

### The problem at scale

One database node handles both writes (slow, exclusive locks) and reads (many, concurrent). Under load, reads starve writes.

```
PRIMARY-REPLICA REPLICATION
─────────────────────────────────────────────────────────────────
     Application
         │
    ┌────▼────┐
    │ Primary │  ← all WRITES go here (INSERT, UPDATE, DELETE)
    │   DB    │
    └────┬────┘
         │ asynchronous replication
    ┌────▼────────────────────┐
    │ Replica 1  │ Replica 2  │  ← all READS go here (SELECT)
    │ (read-only)│ (read-only)│
    └────────────┴────────────┘

Benefits:
  ✅ Read throughput scales linearly with replicas
  ✅ Primary handles only writes (fewer lock conflicts)
  ✅ Replicas can be in different regions (reduced read latency)
  ✅ Replica can be promoted to primary on failure

Caveats:
  ❌ Replication lag — replica data is milliseconds to seconds behind primary
  ❌ "Read your own writes" problem:
       user writes a record → reads immediately from replica → doesn't see it yet
  ❌ More operational complexity
```

### In Spring Boot — routing reads to replicas

```java
// application.properties (production)
spring.datasource.url=jdbc:postgresql://primary-db:5432/usersdb
# Custom read datasource (configure in a @Configuration class)
datasource.read.url=jdbc:postgresql://replica-db:5432/usersdb

// AbstractRoutingDataSource pattern — routes based on @Transactional(readOnly)
@Transactional(readOnly = true)   // → Spring routes to replica datasource
public UserResponse getUserById(Long id) { ... }

@Transactional                     // → Spring routes to primary datasource
public UserResponse createUser(...) { ... }
```

---

## 7. Sharding (Concept)

### What sharding solves

Replication scales **reads** but does nothing for write throughput or storage. If you have 10TB of data, every replica holds all 10TB. If you have 100,000 writes/second, all go to one primary.

**Sharding** splits data across multiple independent databases. Each shard holds a **subset** of the data.

```
WITHOUT SHARDING:
  One DB holds all users: User 1 to User 10,000,000

WITH SHARDING (by userId % 4):
  Shard 0: userId % 4 == 0  → Users 4, 8, 12 ...
  Shard 1: userId % 4 == 1  → Users 1, 5, 9  ...
  Shard 2: userId % 4 == 2  → Users 2, 6, 10 ...
  Shard 3: userId % 4 == 3  → Users 3, 7, 11 ...

Each shard: independent DB, independent primary+replicas
Writes for userId=42 → always go to Shard 2 (42 % 4 = 2)
```

### Sharding strategies

| Strategy | How | Good for | Problem |
|---|---|---|---|
| **Hash sharding** | `userId % N` | Even distribution | Rebalancing when N changes |
| **Range sharding** | userId 1–1M → Shard 1 | Range queries | Hot spots (all new users go to last shard) |
| **Directory sharding** | Lookup table: "user 42 is on shard C" | Flexible | Lookup table is a bottleneck |
| **Geographic** | EU users → EU shard | Data residency (GDPR) | Cross-region queries hard |

### Sharding problems (know for interviews)

```
CROSS-SHARD JOINS:
  "Get all todos for users in city=London" → users may be on 4 different shards
  SQL JOIN across shards is impossible → requires scatter-gather (fan-out query)

RESHARDING:
  Starting with 4 shards, now need 8. Moving data without downtime is hard.
  Consistent hashing reduces this problem (only ~1/N keys need to move).

TRANSACTIONS:
  A transaction spanning two shards requires distributed transactions (2PC or Saga).
  Most teams avoid cross-shard transactions by design (shard key = transaction boundary).
```

> **Interview answer:** "We defer sharding as long as possible. Caching + read replicas handles most scale problems. Sharding is a last resort because of the operational and query complexity it introduces."

---

## 8. Rate Limiting

### Why rate limiting matters

```
WITHOUT RATE LIMITING:
  One bad client (or DDoS) sends 100,000 requests/second
  → Your service is overwhelmed
  → All legitimate users get 503
  → Database falls over
  → Cascading failure

WITH RATE LIMITING:
  Each client gets: 1000 requests/minute
  Excess requests: 429 Too Many Requests (with Retry-After header)
  Legitimate traffic: unaffected
```

### Rate limiting algorithms

```
TOKEN BUCKET (most common — used by AWS, Stripe)
─────────────────────────────────────────────────
  Bucket holds N tokens. Refills at R tokens/second.
  Each request consumes 1 token. No token → 429.

  Allows bursts: if idle for 10 seconds, bucket fills up → burst of N requests OK.
  Smooth over time: sustained rate capped at R requests/second.

FIXED WINDOW COUNTER
─────────────────────────────────────────────────
  Count requests in current 1-minute window.
  Limit: 100 requests per window.
  Window resets at :00 of every minute.

  Problem: boundary attack — 100 requests at :59, 100 more at :01 = 200 requests in 2 seconds.

SLIDING WINDOW LOG
─────────────────────────────────────────────────
  Store timestamp of every request for each user (in Redis sorted set).
  Count requests in last 60 seconds.
  Most accurate but most memory-intensive.

SLIDING WINDOW COUNTER (hybrid — best tradeoff)
─────────────────────────────────────────────────
  Weight = (current_window_count) + (prev_window_count × overlap_fraction)
  e.g., at :45 of current minute: weight = current + prev × 0.25
  Good accuracy, O(1) memory.
```

### In our stack: Spring Cloud Gateway Rate Limiter

```yaml
# application.yml — api-gateway
spring:
  cloud:
    gateway:
      routes:
        - id: todo-service-route
          uri: lb://todo-service
          predicates:
            - Path=/api/todos/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100    # tokens added per second
                redis-rate-limiter.burstCapacity: 200    # max tokens in bucket
                redis-rate-limiter.requestedTokens: 1    # tokens per request
                key-resolver: "#{@userKeyResolver}"      # rate limit per user, not per IP
```

```java
// UserKeyResolver — rate limit per authenticated user (from JWT header)
@Bean
KeyResolver userKeyResolver() {
    return exchange -> Mono.justOrEmpty(
        exchange.getRequest().getHeaders().getFirst("X-User-Id")
    ).defaultIfEmpty("anonymous");
}
```

### Rate limiting at different layers

```
Layer 1 — API Gateway (our implementation above)
  → Per-user, per-endpoint rate limiting
  → Returns 429 before request hits any service
  → Requires Redis for distributed counter

Layer 2 — Service-level (Resilience4j RateLimiter)
  → Protects a specific method from too many concurrent calls
  → Per-instance (not distributed)
  → Good for: protecting a slow downstream call

Layer 3 — Infrastructure (AWS WAF, Cloudflare)
  → IP-based blocking before traffic hits your cluster
  → Handles volumetric DDoS attacks
```

---

## 9. Putting It Together — Scalability Architecture

```
                           ┌──────────────┐
                           │   Client     │
                           └──────┬───────┘
                                  │
                    ┌─────────────▼──────────────┐
                    │     AWS ALB / Nginx         │  ← Server-side LB
                    │  (rate limiting at IP level)│
                    └─────────────┬──────────────┘
                                  │
                    ┌─────────────▼──────────────┐
                    │      API Gateway            │  ← Rate limiting (Redis)
                    │   (JWT auth + rate limit)   │     per user
                    └──────┬──────────────┬───────┘
                           │              │
          ┌────────────────▼──┐      ┌────▼────────────────┐
          │  user-service ×3  │      │  todo-service ×5    │
          │  (stateless)      │      │  (stateless)         │
          └────────┬──────────┘      └──────┬───────────────┘
                   │                        │
          ┌────────▼──────────┐    ┌────────▼───────────────┐
          │  Redis Cache      │    │  Redis Cache           │
          │  (user data)      │    │  (todo lists)           │
          └────────┬──────────┘    └────────┬───────────────┘
                   │                        │
          ┌────────▼──────────┐    ┌────────▼───────────────┐
          │  Primary DB       │    │  Primary DB            │
          │  + 2 replicas     │    │  + 2 replicas           │
          └───────────────────┘    └────────────────────────┘
```

---

## 10. Common Mistakes

| Mistake | Consequence | Fix |
|---|---|---|
| Stateful service (session in memory) | Adding instances breaks user sessions | JWT, Redis session store |
| Caching everything | Stale data, hard-to-debug bugs | Cache only stable, read-heavy data |
| No TTL on cache | Memory exhaustion, infinitely stale data | Always set TTL |
| Sharding too early | Massive complexity for no benefit | Cache + replicas first |
| Rate limiting by IP only | Legitimate users behind NAT all throttled | Rate limit by userId/API key |
| Not accounting for replication lag | "Read your own writes" bugs | Route writes + immediate reads to primary |

---

## 11. Interview Questions — Standard

**Q1: What is the difference between horizontal and vertical scaling?**
> Vertical scaling adds resources to one machine (more CPU/RAM). It has a hard ceiling, costs non-linearly, and has a single point of failure. Horizontal scaling runs more instances. It scales limitlessly with commodity hardware and has no single point of failure. Microservices are designed for horizontal scaling — which is why statelessness is not optional.

**Q2: Why must a service be stateless to scale horizontally?**
> If Instance A stores state (e.g., a user session) in its JVM heap, Instance B doesn't have that state. A request routed to B fails. With 5 instances, you'd need sticky sessions (pinning a user to one instance), which eliminates the load-balancing benefit. Stateless services store nothing in memory that's request-specific — state lives in Redis, the DB, or the JWT token. Any instance can serve any request.

**Q3: What is cache-aside and when would you use it?**
> Cache-aside (lazy loading): on read, check cache first. If miss, read DB, write to cache, return. On write, update DB and invalidate the cache key. Use it when reads far outnumber writes and you can tolerate a brief staleness window (TTL). It's the most common pattern in microservices because cache failures gracefully degrade to DB reads.

**Q4: What is the difference between a read replica and a cache?**
> A read replica is a synchronized copy of the database — it handles full SQL queries, maintains data types and constraints, reflects updates within milliseconds (replication lag). A cache (Redis) is a key-value store in memory — sub-millisecond reads, but manual invalidation required, no query language, and data can be stale by TTL. Use replicas when you need complex queries against fresh data. Use cache for hot, simple lookups (user profile, config).

**Q5: What is replication lag and how do you handle it?**
> Replication lag is the delay between a write on the primary DB and that write appearing on replicas — typically milliseconds but can spike to seconds under heavy load. Problems: a user creates a record then immediately reads it — the read goes to a replica that hasn't received the write yet, returning 404 or stale data. Solutions: (1) route the read immediately after a write to the primary, (2) implement "read your own writes" consistency using a session token, (3) use the cache — write to cache at same time as DB, reads always hit cache.

---

## 12. Tricky Interview Questions

**Q: You cache a user's profile. The user updates their email. Another service reads the stale cached email and sends a notification to the wrong address. How do you fix this?**
> Three layers of fix: (1) Short TTL — set TTL to 60 seconds. Stale window is bounded. (2) Event-driven invalidation — user-service publishes a `UserUpdated` Kafka event. Every service consuming user data subscribes and evicts the cache entry immediately. This is near-real-time with zero polling. (3) Write-through — on update, write to cache and DB atomically. For critical data like email, event-driven invalidation is the right answer because it decouples services while ensuring eventual consistency with a very short stale window.

**Q: With 4 database shards based on `userId % 4`, your service gets 10x more users and you need 8 shards. How do you rebalance without downtime?**
> This is the rehashing problem. With simple modulo, changing N from 4 to 8 remaps ~75% of keys to different shards — huge data migration. The solution is **consistent hashing**: map the hash space to a ring. Adding a shard only remaps 1/N of the keys (the adjacent segment on the ring). Tools like Vitess (MySQL) or partition-aware clients handle this. For most teams, the answer is: design your shard key to allow range-based splitting (split shard 0 into 0a/0b without remapping other shards).

**Q: What's the difference between rate limiting at the API Gateway vs Resilience4j RateLimiter in a service?**
> API Gateway rate limiting is **distributed and per-user** — backed by Redis, it counts requests across all gateway instances, enforced before the request reaches any service. It protects the entire backend from abuse and returns 429 to the client. Resilience4j RateLimiter is **per-instance and per-method** — it limits how fast one service calls another downstream service. It's not distributed (each instance has its own counter). Use gateway rate limiting for client abuse protection; use Resilience4j RateLimiter to protect a specific slow downstream call from being overwhelmed by one service.

---

## 13. Scenario-Based Questions

**Scenario 1:** "Your user-service is getting 50,000 reads/second and 500 writes/second. The DB is the bottleneck. Walk me through your scaling plan."

> Step 1: Add Redis cache with cache-aside. User profiles are stable — TTL of 5 minutes. This eliminates ~90% of DB reads. Step 2: Add read replicas (2-3). Route all `@Transactional(readOnly=true)` queries to replicas. Step 3: Horizontal scale the user-service itself — it's stateless, so adding instances is trivial (Eureka auto-registers, LoadBalancer auto-discovers). Step 4: If write throughput is still a problem — connection pooling tuning (HikariCP), then consider partitioning the users table. Sharding is a last resort.

**Scenario 2:** "A specific user is hammering your API with a script, causing 503s for everyone else. What do you do right now, and what do you build long-term?"

> Right now: identify the user from logs (X-User-Id header), manually block their API key or add an IP block at the WAF/load balancer. Long-term: implement per-user rate limiting at the API Gateway using Spring Cloud Gateway's `RequestRateLimiter` with a `userKeyResolver`. 1000 requests/minute per user. Return 429 with a `Retry-After` header. No legitimate user hits this limit. The bad actor gets 429 immediately, not 503 for everyone.

---

## 14. Quick Revision Cheat Sheet

```
SCALING
  Vertical   = bigger machine. Simple, ceiling, SPOF.
  Horizontal = more instances. No ceiling, needs stateless.
  Stateless  = no per-request state in JVM heap. JWT > sessions.

LOAD BALANCING
  Client-side: Spring Cloud LoadBalancer + Eureka (our stack)
  Server-side: Nginx, AWS ALB
  Algorithms: Round Robin (default), Least Connections, Weighted

REDIS CACHING
  Cache-aside: check cache → miss → DB → store → return  (most common)
  @Cacheable   = read from cache or execute method
  @CacheEvict  = remove entry on update/delete
  @CachePut    = always execute + update cache
  TTL: ALWAYS set one. Event-driven invalidation for critical data.
  Cache: sub-ms, key-value, manual invalidation
  Replica: ms, full SQL, automatic replication

DB REPLICATION
  Primary    = all writes
  Replicas   = all reads
  Lag        = ms delay. Route post-write reads to primary.
  Spring     = @Transactional(readOnly=true) → replica routing

SHARDING
  Splits data across multiple independent DBs
  Hash sharding: userId % N (use consistent hashing to avoid full remap)
  Problems: cross-shard joins, resharding, distributed transactions
  Rule: cache + replicas first, shard last

RATE LIMITING
  Gateway level: Redis-backed, per-user, distributed (TokenBucket)
  Service level: Resilience4j, per-instance, per-method
  Algorithms: Token Bucket (bursts ok), Sliding Window (accurate), Fixed Window (simple)
  Response: 429 Too Many Requests + Retry-After header
```
