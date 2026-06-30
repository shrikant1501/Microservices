# Phase 7 — Centralized Configuration (Spring Cloud Config)

> **80/20 Focus:** The exact problem it solves, how the refresh mechanism works, and the 5 questions that come up in every senior interview. Implementation is minimal — the concept and trade-offs have the highest ROI.

---

## 1. The Problem This Solves

You now have 5 services, each with its own `application.properties`. Production values differ from dev. Kafka bootstrap URL changes. A DB password rotates.

```
CURRENT STATE:
  user-service/src/main/resources/application.properties
  todo-service/src/main/resources/application.properties
  notification-service/src/main/resources/application.properties
  api-gateway/src/main/resources/application.properties
  eureka-server/src/main/resources/application.properties

PROBLEM 1 — Config is baked into the JAR
  To change spring.kafka.bootstrap-servers, you must:
  rebuild → retest → redeploy → restart ALL services.
  Changing one Kafka URL = 5 redeployments.

PROBLEM 2 — Environment inconsistency
  Dev:        spring.datasource.url=jdbc:h2:mem:usersdb
  Staging:    spring.datasource.url=jdbc:postgresql://stg-db:5432/users
  Production: spring.datasource.url=jdbc:postgresql://prod-db:5432/users
  Managing these across services and environments = config sprawl.

PROBLEM 3 — Secrets in code
  spring.datasource.password=supersecret  ← in a Git-committed file
  This is a security disaster.

PROBLEM 4 — Audit gap
  Who changed what config, and when? No history, no rollback.
```

**Spring Cloud Config** solves all four by externalizing config to a central server backed by a Git repository.

---

## 2. How It Works

```
┌─────────────────────────────────────────────────────┐
│              CONFIG SERVER  :8888                   │
│                                                     │
│  Backed by Git repo (or filesystem/Vault/S3)        │
│  ─────────────────────────────────────────────────  │
│  config-repo/                                       │
│    application.yml          ← shared by ALL         │
│    user-service.yml         ← user-service only     │
│    todo-service.yml         ← todo-service only     │
│    user-service-prod.yml    ← prod override         │
└──────────────────────┬──────────────────────────────┘
                       │  HTTP GET /user-service/default
         ┌─────────────┼──────────────┐
         ▼             ▼              ▼
   user-service   todo-service   api-gateway
   (at startup, fetches its config from config server)
```

### Resolution Order (priority: high → low)

```
1. user-service-{profile}.yml  (most specific: service + environment)
2. user-service.yml            (service-specific)
3. application.yml             (shared defaults for all services)
4. Local application.properties (lowest priority — used as fallback)
```

### Startup Flow

```
Service starts
    │
    ▼
bootstrap phase (before Spring context loads)
    │
    ├─ reads spring.application.name = "user-service"
    ├─ reads spring.config.import = "configserver:http://localhost:8888"
    │
    ▼
Fetches: GET http://localhost:8888/user-service/default
    │
    ▼
Config server returns merged properties
    │
    ▼
Spring context initializes with those properties
```

---

## 3. The `@RefreshScope` + `/actuator/refresh` Pattern

This is the most interview-probed feature.

**Problem:** Config is fetched at startup. If you change a value in the Git repo, running services don't see the change until restarted.

**Solution:** `@RefreshScope` + `/actuator/refresh`

```java
@RestController
@RefreshScope   // ← this bean is re-created when /actuator/refresh is called
public class SomeController {

    @Value("${feature.flag.new-ui:false}")
    private boolean newUiEnabled;
}
```

**Trigger a refresh without restart:**
```bash
# After changing config in Git repo:
curl -X POST http://localhost:8081/actuator/refresh

# Spring re-fetches config from config server
# All @RefreshScope beans are destroyed and recreated with new values
# Service keeps running — NO restart
```

**Spring Cloud Bus** (Phase extension — not built here): broadcasts the refresh event to ALL instances via Kafka/RabbitMQ so you don't need to call `/actuator/refresh` on each instance individually.

---

## 4. Config File Naming Convention

```
config-repo/
├── application.yml                  → shared by all services, all profiles
├── application-prod.yml             → shared by all services, prod profile only
│
├── user-service.yml                 → user-service, default profile
├── user-service-dev.yml             → user-service, dev profile
├── user-service-prod.yml            → user-service, prod profile (DB creds, etc.)
│
├── todo-service.yml
└── api-gateway.yml
```

A service with `spring.application.name=user-service` and `spring.profiles.active=prod` receives a **merge** of:
- `application.yml`
- `application-prod.yml`
- `user-service.yml`
- `user-service-prod.yml`

in that priority order.

---

## 5. What Changes in Each Service

Each service adds ONE property to `application.properties`:

```properties
# Tells Spring Boot: fetch config from this server before doing anything else
spring.config.import=optional:configserver:http://localhost:8888
```

The `optional:` prefix means "if config server is unreachable, start anyway with local config." Remove `optional:` in production to force failure-fast if config server is down.

---

## 6. Backends: Git vs Vault vs Filesystem

| Backend | When to use |
|---------|-------------|
| **Git** (default) | Config-as-code, full audit trail, PR review for config changes |
| **HashiCorp Vault** | Secrets (DB passwords, API keys) — encrypted, access-controlled |
| **Filesystem** | Local dev and testing only |
| **AWS S3 / Azure Blob** | Cloud-native deployments without Git server |

> **Production architecture:** Git for non-secret config + Vault for secrets. Config server reads from both.

---

## 7. Key Interview Questions

**Q1. What is Spring Cloud Config and what problem does it solve?**
> Spring Cloud Config externalizes configuration from application JARs to a central server backed by a Git repository. This solves: (1) config baked into JARs requiring redeployment to change a value; (2) environment-specific config sprawl across dozens of files; (3) secrets committed to source code; (4) no audit trail for config changes. Each service fetches its config from the config server at startup, before the Spring context initializes.

**Q2. What is the difference between `application.yml` and `user-service.yml` in the config repo?**
> `application.yml` is the **shared configuration** loaded by every service. `user-service.yml` is **service-specific** configuration loaded only by the service with `spring.application.name=user-service`. When both files define the same property, the service-specific file wins. This hierarchy lets you define defaults once and override only what differs per service.

**Q3. How do you update config without restarting a service?**
> Change the value in the Git config repository. Then call `POST /actuator/refresh` on the service instance. Spring re-fetches all properties from the config server and re-initializes all beans annotated with `@RefreshScope`. The service continues running without downtime. For refreshing all instances of a service simultaneously, Spring Cloud Bus broadcasts the refresh event over Kafka/RabbitMQ so a single curl call propagates to every instance.

**Q4. What is `@RefreshScope` and what are its risks?**
> `@RefreshScope` marks a bean to be lazily re-created the next time it is accessed after a `/actuator/refresh` call. The risk: the old bean is destroyed and the new one is created. If the new config is invalid (bad DB URL, wrong Kafka topic), the bean fails to initialize and requests to that bean fail until the config is corrected. Always validate config changes in staging before pushing to production.

**Q5. What happens if the config server is unavailable when a service starts?**
> With `spring.config.import=optional:configserver:...`, the service starts using its local `application.properties` as a fallback. With `spring.config.import=configserver:...` (no `optional:`), the service **refuses to start** — fail-fast. The `optional:` prefix is appropriate in dev/test. In production, the config server should be highly available (multiple instances behind a load balancer) and `optional:` should be removed so misconfigured services fail immediately rather than silently starting with wrong config.

---

## 8. Tricky Interview Questions

**Q. Is it safe to store database passwords in the Git config repo?**
> No. Git repos — even private ones — have multiple access points (developer machines, CI pipelines, third-party integrations) that increase the risk of credential exposure. Sensitive values should be stored in **HashiCorp Vault** or a cloud secrets manager (AWS Secrets Manager, GCP Secret Manager). Spring Cloud Config supports Vault as a backend. Alternatively, encrypt values in the Git file using Spring Cloud Config's symmetric encryption (`{cipher}...`) and store the encryption key separately in a Vault or environment variable.

**Q. Can two services share the same config key with different values?**
> Yes — that's exactly what the service-specific file is for. `application.yml` defines `server.shutdown=graceful` as a default for all services. `user-service.yml` can override with `server.shutdown=immediate`. The merge gives user-service its specific value while all other services use the default. This is the layered override model.

---

## 9. Quick Revision Cheat Sheet

```
SPRING CLOUD CONFIG — PURPOSE
 └─ Externalize config from JARs to a central Git-backed server
 └─ One place to change a value, all services pick it up
 └─ Audit trail via Git commit history
 └─ Profiles: dev/staging/prod configs in same repo

CONFIG RESOLUTION (highest to lowest priority)
 └─ {service}-{profile}.yml   (user-service-prod.yml)
 └─ {service}.yml             (user-service.yml)
 └─ application-{profile}.yml (application-prod.yml)
 └─ application.yml           (shared defaults)
 └─ local application.properties (fallback)

REFRESH WITHOUT RESTART
 └─ Change value in Git repo
 └─ POST /actuator/refresh → re-fetches config
 └─ @RefreshScope beans are re-created with new values
 └─ Spring Cloud Bus: broadcast refresh to ALL instances via Kafka

CLIENT CONFIG (one line per service)
 └─ spring.config.import=optional:configserver:http://localhost:8888
 └─ "optional:" = use local config if server unreachable (dev only)
 └─ remove "optional:" in production → fail-fast on bad config

BACKENDS
 └─ Git (default) — config-as-code, audit trail
 └─ Vault — secrets, encrypted, access-controlled
 └─ Filesystem — local dev/test only

SECRET HANDLING
 └─ NEVER commit passwords to Git config repo
 └─ Use Vault backend or Spring Cloud Config encryption ({cipher}...)
 └─ Or inject via environment variables / K8s Secrets
```
