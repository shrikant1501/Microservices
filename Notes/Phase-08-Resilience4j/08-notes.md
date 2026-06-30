# Phase 8 — Resilience4j (Circuit Breaker, Retry, Bulkhead, Rate Limiter)

> **The most interview-critical phase.** Every senior microservices interview has at least one question about what happens when a downstream service fails. This phase gives you both the theory AND the working code to demonstrate it.

---

## 1. The Problem — Why Resilience Exists

After Phase 3 we noted this raw failure scenario:

```
todo-service calls user-service via Feign.
user-service is slow (responding in 60 seconds).

Request 1  → thread blocked waiting (60s)
Request 2  → thread blocked waiting (60s)
Request 3  → thread blocked waiting (60s)
...
Request 200 → all threads in todo-service thread pool are now blocked
              waiting for user-service.

New request arrives at todo-service for /api/todos/42 (doesn't even touch user-service)
→ No threads available → request queues → times out → 503 to client

todo-service is now DOWN because of user-service slowness.
This is a CASCADING FAILURE.
```

**Resilience4j** is a fault-tolerance library that provides four patterns to prevent this:

| Pattern | Problem it solves |
|---------|------------------|
| **Circuit Breaker** | Stop hammering a failing service — fail fast instead |
| **Retry** | Transient failures (network blip) — try again automatically |
| **Bulkhead** | Isolate failures — cap threads per dependency |
| **Rate Limiter** | Protect your service from being overwhelmed |

---

## 2. Circuit Breaker — The Most Important Pattern

### The State Machine (interviewers draw this on whiteboards)

```
                    failure rate > threshold
    ┌──────────────────────────────────────────┐
    │                                          ▼
 ┌──┴───┐     failure rate             ┌────────────┐
 │      │     exceeds threshold        │            │
 │CLOSED│ ──────────────────────────► │    OPEN    │
 │      │                             │            │
 └──────┘                             └─────┬──────┘
    ▲                                       │
    │ success rate                          │ after waitDuration
    │ above threshold                       │ (e.g. 30 seconds)
    │                                       ▼
 ┌──┴──────────┐                    ┌────────────────┐
 │             │◄───────────────────│                │
 │   CLOSED    │  if calls succeed  │  HALF-OPEN     │
 │  (reset)    │                    │ (test N calls) │
 └─────────────┘                    └────────────────┘
                                    if calls fail → OPEN again
```

**State explanations:**

| State | Behaviour | Transition |
|-------|-----------|-----------|
| **CLOSED** | Normal operation. Calls go through. | → OPEN when failure rate > threshold (e.g. 50% in last 10 calls) |
| **OPEN** | Calls fail immediately without hitting the service. Returns fallback. | → HALF-OPEN after `waitDurationInOpenState` (e.g. 30s) |
| **HALF-OPEN** | Allows N test calls through. | → CLOSED if they succeed; → OPEN if they fail |

**Why this is genius:** In the OPEN state, todo-service doesn't block any threads waiting for user-service. It returns the fallback *immediately* (microseconds). Thread exhaustion is impossible.

---

## 3. The 4 Annotations — What You Write in Code

```java
// CIRCUIT BREAKER — wrap a service call
@CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
public UserResponse getUser(Long id) {
    return userServiceClient.getUserById(id);
}

// RETRY — retry on failure with backoff
@Retry(name = "user-service", fallbackMethod = "getUserFallback")
public UserResponse getUser(Long id) { ... }

// BULKHEAD — limit concurrent calls
@Bulkhead(name = "user-service", type = Bulkhead.Type.SEMAPHORE)
public UserResponse getUser(Long id) { ... }

// RATE LIMITER — limit calls per time window
@RateLimiter(name = "user-service")
public UserResponse getUser(Long id) { ... }

// COMBINE THEM (most common production pattern)
@CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
@Retry(name = "user-service")
public UserResponse getUser(Long id) { ... }
```

**The fallback method** — this is what executes when the circuit is OPEN or the call fails:

```java
// Must have the SAME return type and parameters + an extra Throwable parameter
public UserResponse getUserFallback(Long id, Throwable ex) {
    log.warn("user-service unavailable for id={}, using fallback. Reason: {}", id, ex.getMessage());
    // Return a degraded response — enough for the caller to continue
    UserResponse fallback = new UserResponse();
    fallback.setId(id);
    fallback.setName("Unknown User");
    return fallback;
}
```

> **Interview Q: "What is a fallback and when should you use one?"**
> A fallback is a degraded response returned when the real call fails. Use it when: (1) the partial/stale data is better than an error, (2) the failure is non-critical (e.g., showing user name on a todo — show "Unknown" rather than failing the todo fetch entirely). Do NOT use a fallback that silently hides critical failures — payment processing must fail loudly, not silently return "payment processed" from a fallback.

---

## 4. Configuration — The Numbers That Matter

```yaml
resilience4j:
  circuitbreaker:
    instances:
      user-service:                    # matches @CircuitBreaker(name = "user-service")
        slidingWindowSize: 10          # evaluate failure rate over last 10 calls
        failureRateThreshold: 50       # open if >50% of last 10 calls fail
        waitDurationInOpenState: 30s   # stay OPEN for 30s before trying HALF-OPEN
        permittedNumberOfCallsInHalfOpenState: 3  # allow 3 test calls in HALF-OPEN
        minimumNumberOfCalls: 5        # need at least 5 calls before evaluating
        slowCallDurationThreshold: 3s  # calls > 3s count as slow (failures)
        slowCallRateThreshold: 50      # open if >50% of calls are slow

  retry:
    instances:
      user-service:
        maxAttempts: 3                 # try 3 times total
        waitDuration: 1s              # wait 1s between attempts
        retryExceptions:              # only retry these
          - java.io.IOException
          - feign.FeignException$ServiceUnavailable

  bulkhead:
    instances:
      user-service:
        maxConcurrentCalls: 10        # max 10 concurrent calls to user-service
        maxWaitDuration: 100ms        # wait 100ms for a permit before failing fast
```

---

## 5. Execution Order — Critical for Interviews

When you stack multiple annotations:

```java
@CircuitBreaker(name = "user-service", fallbackMethod = "fallback")
@Retry(name = "user-service")
@Bulkhead(name = "user-service")
public UserResponse getUser(Long id) { ... }
```

**Execution order (outer → inner):**
```
Bulkhead → Retry → CircuitBreaker → actual call
```

So:
1. Bulkhead checks: are there threads available? If not → fail fast
2. Retry wraps the circuit breaker call
3. CircuitBreaker checks: is circuit OPEN? If yes → fail fast (fallback)
4. If CLOSED → call user-service
5. If call fails → CircuitBreaker records failure
6. Retry retries from step 3 (if not exhausted)
7. If all retries exhausted → fallback is called

> **Interview Q: "If Retry is applied with 3 attempts and CircuitBreaker opens on the 2nd attempt, what happens?"**
> The 3rd retry attempt hits the Circuit Breaker which is now OPEN — it returns immediately without calling user-service. The Retry sees the `CallNotPermittedException` — depending on config, it may not retry that exception type. The fallback is invoked. This is why you explicitly configure `ignoreExceptions` to not retry `CallNotPermittedException`.

---

## 6. Actuator Endpoints — Show This in Interviews

```bash
# See the current state of all circuit breakers
GET /actuator/circuitbreakers

# Response:
{
  "circuitBreakers": {
    "user-service": {
      "state": "CLOSED",
      "failureRate": "0.0%",
      "slowCallRate": "0.0%",
      "bufferedCalls": 5,
      "failedCalls": 0
    }
  }
}

# Stop user-service, make a few calls, then check again:
{
  "user-service": {
    "state": "OPEN",     ← circuit is now open
    "failureRate": "60.0%"
  }
}
```

This is something you can **demonstrate live** in an interview — stop a service, trigger the circuit breaker, show the state change, show the fallback, wait 30s, show HALF-OPEN → CLOSED. This is interview gold.

---

## 7. Implementation in todo-service

Three changes:
1. Add Resilience4j dependency to `todo-service/pom.xml`
2. Add Resilience4j config to `application.properties`
3. Annotate the Feign call in `TodoService` with `@CircuitBreaker` + fallback

---

## 8. Key Interview Questions

**Q1. What is a Circuit Breaker and what problem does it solve?**
> A Circuit Breaker prevents cascading failures. Without it, when a downstream service is slow, callers block threads waiting — eventually exhausting their thread pool and becoming unresponsive themselves. The Circuit Breaker monitors failure rates. When failures exceed a threshold, it "opens" — subsequent calls fail immediately (microseconds) with a fallback, releasing threads. After a wait period it enters HALF-OPEN, allows test calls through, and closes again if they succeed. It protects the caller from a failing dependency.

**Q2. What are the three states of a Circuit Breaker?**
> CLOSED (normal — calls go through), OPEN (circuit is tripped — calls fail immediately with fallback, no actual call made), HALF-OPEN (trial period — a limited number of calls are allowed through to test if the service has recovered; success → CLOSED, failure → OPEN again).

**Q3. What is the difference between Circuit Breaker and Retry?**
> They solve different problems. Retry is for **transient failures** — a brief network hiccup that succeeds on the 2nd or 3rd attempt. Circuit Breaker is for **persistent failures** — a service that is down for seconds or minutes. Using Retry on a persistently-failing service makes things worse (multiplies load on the struggling service). The correct combination: Retry handles transient errors first; Circuit Breaker handles persistent failures when retries are exhausted. Retry sits inside the Circuit Breaker (retries happen before the CB records a failure).

**Q4. What is a Bulkhead?**
> Bulkhead prevents one dependency from consuming all available resources. Like a ship's bulkhead that isolates compartments so one flood doesn't sink the whole ship. In Resilience4j: SemaphoreBulkhead limits concurrent calls (e.g., max 10 simultaneous calls to user-service). ThreadPoolBulkhead uses a dedicated thread pool per dependency. If user-service is slow, only the 10 bulkhead slots are occupied — the rest of todo-service's threads remain free for other requests.

**Q5. What is the difference between Semaphore Bulkhead and Thread Pool Bulkhead?**
> SemaphoreBulkhead limits the number of concurrent calls using a counting semaphore — it runs in the caller's thread. It's lightweight but the caller's thread IS blocked during the call. ThreadPoolBulkhead offloads the call to a dedicated thread pool — the caller's thread is freed immediately (non-blocking). ThreadPoolBulkhead is better for true isolation but adds complexity and is better suited to reactive/async code. For standard Spring Boot (servlet), SemaphoreBulkhead is the default.

---

## 9. Tricky Interview Questions

**Q. Can a Circuit Breaker make things worse?**
> Yes — if the fallback silently swallows failures. Example: payment-service circuit opens, fallback returns "payment successful" — you've just processed orders you'll never charge for. Always design fallbacks to match the criticality of the operation. For read operations (get user name), a stale/default fallback is fine. For write operations (payments, inventory deduction), the fallback should return a clear error — never silently succeed.

**Q. What is the difference between slow call threshold and failure rate threshold?**
> Failure rate threshold opens the circuit when N% of calls throw exceptions. Slow call threshold opens the circuit when N% of calls exceed a duration threshold — even if they eventually succeed. This matters because a service responding in 30 seconds is arguably worse than one that fails fast with a 500. Both contribute to opening the circuit.

**Q. What happens during HALF-OPEN if the first call succeeds but the second fails?**
> It depends on `permittedNumberOfCallsInHalfOpenState`. If set to 3 and 2 out of 3 test calls fail (≥50% failure rate threshold), the circuit goes back to OPEN. If 3 out of 3 succeed, it goes to CLOSED. The HALF-OPEN state evaluates all permitted calls together before making the transition decision.

---

## 10. Quick Revision Cheat Sheet

```
CIRCUIT BREAKER STATE MACHINE
 └─ CLOSED  → normal, calls go through, monitors failure rate
 └─ OPEN    → calls fail immediately (fallback), no real call made
             → after waitDurationInOpenState → HALF-OPEN
 └─ HALF-OPEN → N test calls allowed
              → success → CLOSED
              → failure → OPEN

OPENS WHEN
 └─ failureRateThreshold exceeded (e.g. 50% of last 10 calls fail)
 └─ OR slowCallRateThreshold exceeded (e.g. 50% of calls > 3s)

KEY NUMBERS TO KNOW
 └─ slidingWindowSize: how many calls to evaluate (default 100)
 └─ minimumNumberOfCalls: don't evaluate until this many calls made
 └─ waitDurationInOpenState: how long to stay OPEN (default 60s)
 └─ permittedNumberOfCallsInHalfOpenState: test calls (default 10)

RETRY — use for transient failures
 └─ maxAttempts, waitDuration, retryExceptions
 └─ Do NOT retry CallNotPermittedException (CB open)

BULKHEAD — isolate resource usage
 └─ Semaphore: limit concurrent calls (same thread)
 └─ ThreadPool: dedicated thread pool (offloads call)

EXECUTION ORDER (outer → inner)
 └─ Bulkhead → RateLimiter → Retry → CircuitBreaker → actual call

FALLBACK METHOD RULES
 └─ Same return type + same parameters + one extra Throwable param
 └─ DO use for: non-critical reads (show "Unknown" vs hard fail)
 └─ DO NOT use for: writes, payments — fail loudly not silently

ACTUATOR
 └─ GET /actuator/circuitbreakers → live state, failure rate
 └─ GET /actuator/retries → retry statistics
```
