# Phase 17 — Deployment
## (Rolling · Blue-Green · Canary · Health Checks · Readiness vs Liveness · Kubernetes)

> **80/20 Focus:** Interviewers ask "how do you deploy without downtime?" and "what's the difference between readiness and liveness probes?" Know all three deployment strategies and both probe types cold.

---

## 1. The Problem

Deploying a monolith meant: stop the server, copy the new JAR, start the server. 2–5 minutes of downtime. Acceptable in 2005. Not acceptable today.

```
REQUIREMENTS FOR MODERN DEPLOYMENT:
  Zero downtime (users never see 503 during deployment)
  Instant rollback (if v2 has a bug, revert to v1 in seconds)
  Gradual rollout (test v2 with 5% of users before full release)
  Health-based promotion (don't send traffic until service is ready)
  Safe for microservices (10 services, each deploying independently)
```

---

## 2. Rolling Deployment

### How it works

Replace instances one at a time (or in small batches). At any moment, both old and new versions serve traffic.

```
BEFORE: 5 instances of v1
  [v1] [v1] [v1] [v1] [v1]

STEP 1: Replace 1 instance
  [v2] [v1] [v1] [v1] [v1]   ← 20% traffic to v2

STEP 2: Replace another
  [v2] [v2] [v1] [v1] [v1]   ← 40% traffic to v2

STEP 3-5: Continue until all replaced
  [v2] [v2] [v2] [v2] [v2]   ← 100% on v2

If v2 has issues during the rollout:
  Stop the rollout. Existing v1 instances still serve traffic.
  Roll back: replace v2 instances with v1.
```

### Characteristics

```
✅ Zero downtime (always instances serving traffic)
✅ Low resource overhead (no extra infrastructure needed)
✅ Simple to implement (default in Kubernetes)
❌ Two versions run simultaneously → API must be backward compatible
❌ If rollback needed mid-rollout, it's a second rolling deployment
❌ Slow for large deployments (many instances to replace)
❌ Hard to test v2 in isolation (mixed traffic)
```

### Critical requirement: backward compatibility

```
During a rolling deployment of todo-service:
  v1 instances write to DB with old schema
  v2 instances write to DB with NEW schema

If v2 adds a new non-nullable column:
  v1 instances don't know about the column → DB error
  
SOLUTION: Expand-Contract (Parallel Change) pattern
  Phase 1: Add column as NULLABLE (backward compatible). Deploy.
  Phase 2: Deploy v2 that writes the new column.
  Phase 3: Add NOT NULL constraint after all v1 instances are gone.
  Phase 4: Remove old code path.
```

---

## 3. Blue-Green Deployment

### How it works

Maintain two identical production environments. Switch all traffic at once.

```
BEFORE DEPLOYMENT:
  Blue (live): 5 instances of v1
  Green (idle): empty (or running v1 from last deployment)
  Load Balancer → Blue (100% traffic)

STEP 1: Deploy v2 to Green
  Blue (live): 5 × v1    ← 100% traffic still here
  Green (staged): 5 × v2  ← 0% traffic (warm up, smoke test)

STEP 2: Smoke test Green
  Run automated tests against Green endpoint
  Manual QA if needed
  Green is running but not receiving production traffic

STEP 3: Switch load balancer
  Blue: 5 × v1   ← 0% traffic (kept alive for rollback)
  Green: 5 × v2  ← 100% traffic
  
  This switch takes MILLISECONDS (just a DNS/LB config change)

STEP 4: Monitor
  If v2 issues detected → switch LB back to Blue (rollback in seconds)
  If v2 is stable → decommission Blue (or keep for next deployment)
```

### Characteristics

```
✅ Zero downtime (traffic switch is instant)
✅ Instant rollback (flip switch back — seconds)
✅ Full v2 smoke test before ANY production traffic hits it
✅ No two versions running simultaneously (clean cutover)
❌ 2x infrastructure cost (two full environments)
❌ Database migration challenge (both envs share one DB)
❌ Warm-up time needed (v2 must warm up caches before receiving traffic)
```

### Database challenge

```
Blue (v1) and Green (v2) must share the same database.
v2's DB migration must be backward compatible with v1.
Same Expand-Contract rule applies.

Alternative: Blue-Green with DB switch too (complex, rarely done)
  Blue: App v1 + DB v1
  Green: App v2 + DB v2
  Data sync between DBs during cutover window
  
Most teams: shared DB, forward-compatible migrations.
```

---

## 4. Canary Deployment

### How it works

Route a small percentage of production traffic to the new version. Gradually increase if stable.

```
STEP 1: Deploy v2, route 5% of traffic
  v1: 95% of traffic
  v2:  5% of traffic (the "canary")

  Monitor: error rate, p99 latency, business metrics

STEP 2: Canary looks healthy → increase to 25%
  v1: 75%
  v2: 25%

STEP 3: Still healthy → 50%
STEP 4: Still healthy → 100% on v2
STEP 5: Decommission v1

If canary shows problems at any step:
  Route 100% back to v1 (< 5% of users were affected)
```

### Traffic routing strategies for canary

```
BY PERCENTAGE (random):
  5% of all users randomly get v2
  Simple to implement
  Same user may get v1 on one request and v2 on the next

BY USER COHORT (sticky):
  Specific users always get v2 (e.g., employees, beta testers)
  userId hash → consistent routing
  Better for feature testing (same user always sees same version)

BY HEADER (manual):
  Add header X-Canary: true → routed to v2
  Used for testing from internal tools without affecting real users

BY GEOGRAPHY:
  Route US-East region to v2, keep EU on v1
  Region-based rollout reduces blast radius
```

### Canary in Kubernetes (with Istio/Argo Rollouts)

```yaml
# Argo Rollouts canary strategy
spec:
  strategy:
    canary:
      steps:
        - setWeight: 5        # 5% to canary
        - pause: {duration: 10m}  # wait 10 minutes, monitor metrics
        - setWeight: 25       # 25% to canary
        - pause: {duration: 10m}
        - setWeight: 50
        - pause: {duration: 10m}
        # If all pauses pass → 100% automatically
      analysis:
        successCondition: "result[0] >= 0.95"  # 95% success rate
        # Automatic rollback if below threshold
```

### Characteristics

```
✅ Minimum blast radius (only 5% of users on risky version)
✅ Real production traffic tests new version
✅ Data-driven promotion (metrics decide, not just smoke tests)
✅ Early detection of performance regressions
❌ Two versions running simultaneously (backward compat required)
❌ Complex routing infrastructure needed
❌ Statistical significance — need enough traffic for meaningful metrics
❌ Debugging harder (which version did this user get?)
```

### Choosing the right strategy

| Criteria | Rolling | Blue-Green | Canary |
|---|---|---|---|
| Zero downtime | ✅ | ✅ | ✅ |
| Instant rollback | ❌ (minutes) | ✅ (seconds) | ✅ (seconds) |
| Infrastructure cost | Low | 2× | Low-Medium |
| Real traffic test | No | No | Yes |
| Risk exposure | Medium | Low | Minimal |
| Complexity | Low | Medium | High |
| **Best for** | Low-risk changes | Critical releases | Uncertain changes |

---

## 5. Health Checks

### Why health checks exist

```
A service can be running (process exists) but broken:
  - DB connection pool exhausted
  - Kafka consumer not connected
  - Memory leak causing OOM responses
  - Configuration error preventing proper initialisation

Without health checks:
  Load balancer sends traffic to a broken instance → users get 500 errors

With health checks:
  Load balancer polls /actuator/health every 10s
  Broken instance returns 503 → removed from rotation
  Healthy instances serve all traffic
  Broken instance restarts → passes health check → re-added to rotation
```

### Spring Boot Actuator Health

```
GET /actuator/health

{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "H2",
        "validationQuery": "isValid()"
      }
    },
    "kafka": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 499963174912,
        "free": 234567890123,
        "threshold": 10485760
      }
    }
  }
}
```

Auto-included health indicators (zero config):
- DB (HikariCP connection test)
- Kafka producer
- Disk space
- Eureka client
- Redis (if configured)

Custom health indicator:

```java
@Component
public class ExternalApiHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // Check something critical to this service
        if (canReachExternalPaymentApi()) {
            return Health.up()
                    .withDetail("payment-api", "reachable")
                    .build();
        }
        return Health.down()
                .withDetail("payment-api", "unreachable")
                .withDetail("reason", "connection refused")
                .build();
    }
}
```

---

## 6. Readiness vs Liveness Probes

This is the **most common Kubernetes interview question for Java developers**.

### Liveness Probe

> **"Is the application alive? Should Kubernetes restart it?"**

```
LIVENESS = Is the JVM running and responding to any request?

If liveness probe FAILS:
  → Kubernetes kills the container and restarts it (fresh start)

What it should check:
  ✅ Can the app respond to HTTP at all? (/actuator/health/liveness)
  ✅ Is the JVM not deadlocked?
  ✅ Is memory not critically exhausted?

What it should NOT check:
  ❌ Database connectivity (DB might be temporarily unavailable — don't restart the app)
  ❌ Kafka connectivity (Kafka might restart — don't kill the app)
  ❌ Downstream service health (their problems ≠ our app is broken)

If you include DB in liveness: DB goes down → liveness fails → 
  Kubernetes restarts your app → app comes back up → liveness fails again (DB still down)
  → Kubernetes restarts again → CrashLoopBackOff → all your instances gone
  The DB problem killed your service even though your service was FINE.
```

### Readiness Probe

> **"Is the application ready to serve traffic? Should the load balancer send it requests?"**

```
READINESS = Is the app ready to handle production traffic?

If readiness probe FAILS:
  → Kubernetes removes instance from load balancer (no restarts)
  → Once readiness passes again → traffic resumes automatically

What it should check:
  ✅ Is Spring context fully initialised? (/actuator/health/readiness)
  ✅ DB connection pool has connections?
  ✅ Kafka consumer connected?
  ✅ Caches warmed up?
  ✅ Downstream critical dependencies reachable?

USE CASE:
  App startup: Spring context takes 20s to initialise.
  Without readiness: LB sends traffic at second 5 → 503 errors.
  With readiness: LB waits until /actuator/health/readiness → UP → THEN sends traffic.

  DB connection pool exhausted under load:
  Readiness: DOWN → removed from LB rotation → other instances absorb traffic
  Once pool recovers: readiness UP → added back to rotation
  Zero restarts. Zero downtime.
```

### Spring Boot 2.3+ automatic probes

```properties
# application.properties
management.endpoint.health.probes.enabled=true
# Enables two sub-paths automatically:
# /actuator/health/liveness  → LivenessStateHealthIndicator
# /actuator/health/readiness → ReadinessStateHealthIndicator
```

### Kubernetes deployment with probes

```yaml
# kubernetes/todo-service-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: todo-service
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: todo-service
          image: microlearning/todo-service:v2.1.0
          ports:
            - containerPort: 8082
          
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness    # lightweight check
              port: 8082
            initialDelaySeconds: 30    # wait 30s before first check (JVM startup)
            periodSeconds: 10          # check every 10s
            failureThreshold: 3        # fail 3 times before restart

          readinessProbe:
            httpGet:
              path: /actuator/health/readiness   # comprehensive check
              port: 8082
            initialDelaySeconds: 20    # check sooner (app may be ready before 30s)
            periodSeconds: 5           # check every 5s (faster traffic routing)
            failureThreshold: 3        # 3 failures → remove from LB

          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
```

---

## 7. Kubernetes Core Concepts

### The minimum Kubernetes vocabulary for microservices interviews

```
POD
  Smallest deployable unit. One or more containers.
  Usually 1 container = 1 pod in microservices.
  Pods are ephemeral — they die and are replaced. Never rely on pod IP.

DEPLOYMENT
  Manages a set of identical pods. Handles rolling updates, rollbacks.
  "I want 3 replicas of todo-service always running"
  kubectl rollout undo deployment/todo-service  ← instant rollback

SERVICE (Kubernetes Service)
  Stable network endpoint (ClusterIP/DNS) that routes to pods.
  Even as pods restart (new IPs), the Service IP stays the same.
  Types: ClusterIP (internal), NodePort (external), LoadBalancer (cloud LB)

INGRESS
  HTTP routing rules into the cluster from outside.
  "Route /api/users → user-service, /api/todos → todo-service"
  In microservices: Ingress replaces or complements your API Gateway.

CONFIGMAP
  Non-sensitive configuration as key-value pairs.
  Mounted as env vars or files into pods.
  Replaces Spring Cloud Config for infrastructure-level config.

SECRET
  Sensitive configuration (passwords, API keys).
  Base64 encoded (NOT encrypted by default — use Vault or Sealed Secrets).
  Mounted same way as ConfigMap.

HORIZONTAL POD AUTOSCALER (HPA)
  Automatically scale pod count based on CPU/memory/custom metrics.
  "Keep CPU < 70%. If above, add pods. If below, remove pods."
  min: 2, max: 20 replicas, targetCPUUtilizationPercentage: 70

NAMESPACE
  Virtual cluster. Isolates resources by team/environment.
  namespaces: dev, staging, production
  Services can talk cross-namespace using: service.namespace.svc.cluster.local
```

### Kubernetes vs our Docker Compose setup

| Feature | Docker Compose | Kubernetes |
|---|---|---|
| Startup order | `depends_on` + healthcheck | Readiness probes + init containers |
| Scaling | `replicas` (static) | HPA (dynamic, metric-based) |
| Rolling update | Not built-in | Default deployment strategy |
| Service discovery | Container name | Kubernetes Service + DNS |
| Config management | env vars in compose | ConfigMap + Secrets |
| Health checks | `healthcheck:` | Liveness + Readiness probes |
| Load balancing | Docker DNS round-robin | kube-proxy + Service |
| Production-ready | No (dev/learning) | Yes |

---

## 8. Interview Questions

**Q1: What is the difference between rolling, blue-green, and canary deployments?**
> Rolling replaces instances gradually — both versions coexist briefly, no extra infrastructure needed, rollback is another rolling deployment (minutes). Blue-green maintains two full environments — switch all traffic instantly, instant rollback (flip the LB), costs 2× infrastructure. Canary routes a small % to the new version — minimum blast radius, data-driven promotion, two versions coexist. Choice: rolling for low-risk changes, blue-green for critical releases needing instant rollback, canary for uncertain changes you want to validate with real traffic.

**Q2: What is the difference between a readiness probe and a liveness probe?**
> Liveness: "Is the app alive?" If it fails, Kubernetes restarts the container. Should only check if the JVM itself is responsive — NOT external dependencies. If you include DB in liveness, a DB outage causes all your pods to restart in a CrashLoopBackOff, making a DB problem into a total service outage. Readiness: "Is the app ready to serve traffic?" If it fails, Kubernetes removes the pod from the load balancer — no restarts. Should include DB, Kafka, and other critical dependencies. When the dependency recovers, readiness passes and traffic resumes automatically.

**Q3: Why must APIs be backward compatible during rolling deployments?**
> During a rolling deployment, v1 and v2 run simultaneously. Requests may be routed to either. If v2 changes the API (renames a JSON field, removes a parameter, adds a non-nullable DB column), v1 clients calling v2 will fail. The solution is the Expand-Contract pattern for DB changes: first add the column as nullable (backward compatible), deploy v2 that writes to it, then add the NOT NULL constraint after all v1 instances are replaced.

**Q4: What happens if you put database health in the liveness probe?**
> When the database has a brief outage (restart, maintenance, network blip), the liveness probe fails on all instances. Kubernetes restarts all pods. The pods come back up, but the DB is still not ready, liveness fails again, Kubernetes restarts again. This loop is called CrashLoopBackOff. Your service is completely down even though the application itself is perfectly healthy. The DB problem became a total service failure that self-perpetuates. Always put DB health in readiness (remove from LB, don't restart).

---

## 9. Tricky Interview Questions

**Q: You're doing a canary deployment and 5% of users are getting errors. How do you decide whether to roll back?**
> Compare canary metrics against baseline (v1): error rate (>1% delta → rollback), p99 latency (>10% increase → investigate), business metrics (conversion rate, orders per minute). Don't just look at HTTP 500s — subtle bugs might show as increased DB errors, decreased business KPIs, or elevated Kafka consumer lag. The decision is automated in mature systems: Argo Rollouts with metric analysis templates automatically rolls back if error rate exceeds threshold within the canary window.

**Q: A Spring Boot service takes 45 seconds to start (loading config, warming caches, connecting to dependencies). How do you configure probes to avoid 503s during deployment?**
> Set `initialDelaySeconds` on the readiness probe to 45+ seconds (or use `startupProbe` in Kubernetes 1.16+, which allows a longer startup window before liveness/readiness take over). The `startupProbe` is specifically designed for slow-starting applications: it has its own `failureThreshold` and once it succeeds, it hands off to the regular liveness and readiness probes. Without this, the liveness probe's `failureThreshold × periodSeconds` must cover the full startup time, which means liveness is also slow to detect actual crashes during normal operation.

---

## 10. Quick Revision Cheat Sheet

```
DEPLOYMENT STRATEGIES
  Rolling    → replace instances one by one. Low cost. Rollback = minutes.
               Both versions run simultaneously → backward compat required.
  Blue-Green → two full envs. Switch LB. Rollback = seconds. Cost = 2×.
               DB migrations must be backward compatible.
  Canary     → 5% → 25% → 50% → 100%. Real traffic test. Instant rollback.
               Need routing infrastructure (Istio, Argo Rollouts).

HEALTH CHECKS
  /actuator/health          → overall health (for LB, monitoring)
  /actuator/health/liveness → is JVM alive? (K8s liveness probe)
  /actuator/health/readiness→ is app ready for traffic? (K8s readiness probe)
  
  Liveness:  only JVM responsiveness. NO external deps. Failure = restart.
  Readiness: DB + Kafka + downstream. Failure = remove from LB, no restart.
  
  DB in liveness probe = CrashLoopBackOff when DB restarts. Never do this.

KUBERNETES VOCABULARY
  Pod        = one running container (ephemeral, dies and respawns)
  Deployment = manages N identical pods, handles rolling updates
  Service    = stable DNS/IP → routes to pods (even as pods restart)
  Ingress    = HTTP routing rules from outside cluster
  ConfigMap  = non-sensitive config (env vars)
  Secret     = sensitive config (passwords, keys) — not encrypted by default
  HPA        = auto-scale pods based on CPU/memory/custom metrics
  Namespace  = virtual isolation (dev/staging/prod)

PROBE SETTINGS (Spring Boot 45s startup)
  startupProbe: failureThreshold=30, periodSeconds=3 (90s total window)
  livenessProbe: initialDelaySeconds=0 (startupProbe handles startup)
  readinessProbe: periodSeconds=5, failureThreshold=3

BACKWARD COMPATIBILITY (rolling/canary)
  Expand-Contract for DB migrations:
    1. Add column NULLABLE (both versions work)
    2. Deploy v2 (writes new column)
    3. Add NOT NULL constraint (v1 gone)
    4. Remove old code
  JSON fields: never remove/rename mid-rollout. Add new, deprecate old.
```
