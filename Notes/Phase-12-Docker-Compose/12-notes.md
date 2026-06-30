# Phase 12 — Docker Compose (Short Reference)
> Run the entire microservices system locally with one command.

---

## The Problem Docker Compose Solves

Without Docker:
```
# Terminal 1         # Terminal 2         # Terminal 3  ...  # Terminal 7
cd eureka-server     cd config-server     cd user-service    cd api-gateway
mvn spring-boot:run  mvn spring-boot:run  mvn spring-boot:run mvn spring-boot:run
```
Plus: manually start Kafka, Zookeeper, Zipkin. Set environment variables. Get startup ORDER right.

With Docker Compose:
```bash
docker compose up
# All 10 containers start in dependency order. Done.
```

---

## Key Concepts (Interview-Relevant)

| Concept | What It Means |
|---|---|
| **Image** | Snapshot of your app + JVM + OS layer. Built once, run anywhere. |
| **Container** | Running instance of an image. Isolated process. |
| **`depends_on`** | Container start ORDER. Does NOT wait for app readiness — use `healthcheck` for that. |
| **`healthcheck`** | Polls an endpoint. Dependent services wait until `healthy` before starting. |
| **Named network** | All services on same Docker network → reach each other by container name, not IP. |
| **Environment override** | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` overrides `application.properties` at runtime. |
| **Volume** | Persists data outside container lifecycle (DB data, config files). |

### Why `depends_on` alone is NOT enough
```yaml
depends_on:
  - kafka          # only waits for container to START, not for Kafka to be READY
```
Kafka takes ~5s to be ready after the container starts. Services connecting immediately will fail.
**Fix:** use `depends_on` with `condition: service_healthy` + a `healthcheck` on Kafka.

### Container name = hostname on Docker network
```
# In docker-compose.yml:
services:
  kafka:
    container_name: kafka   ← this IS the hostname

# In user-service env:
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092   ← resolves via Docker's internal DNS
```

---

## Multi-Stage Dockerfile (Production Pattern)

```dockerfile
# Stage 1: Build — fat JDK image, only used at build time
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q        # cache deps layer
COPY src ./src
RUN ./mvnw package -DskipTests -q          # build the JAR

# Stage 2: Runtime — thin JRE image, ~200MB vs ~600MB
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Why multi-stage?**
- Build stage: needs Maven + full JDK (~600MB)
- Runtime stage: only needs JRE (~200MB)
- Final image: ~200MB instead of ~600MB
- Build tools never ship to production

---

## Startup Order for Our System

```
Phase 1: Infrastructure
  zookeeper → kafka (waits: zookeeper healthy)
  zipkin    (independent)

Phase 2: Spring Boot Infrastructure
  eureka-server  (waits: nothing)
  config-server  (waits: nothing)

Phase 3: Business Services
  user-service         (waits: eureka healthy + config healthy + kafka healthy)
  todo-service         (waits: eureka healthy + config healthy + kafka healthy + user-service healthy)
  notification-service (waits: eureka healthy + kafka healthy)

Phase 4: Edge
  api-gateway          (waits: eureka healthy)
```

---

## Quick Revision

```
docker compose up -d          # start all, detached
docker compose up -d kafka    # start only kafka (and its depends_on)
docker compose logs -f todo-service    # follow logs
docker compose ps             # see status + health of all containers
docker compose down           # stop + remove containers (keep volumes)
docker compose down -v        # stop + remove containers AND volumes (wipe DB)
docker compose build          # rebuild all images
docker compose build user-service  # rebuild one image

# Connect to running container:
docker exec -it user-service sh

# See all networks:
docker network ls

# Inspect which containers are on a network:
docker network inspect microservices-network
```

---

## Common Interview Questions

**Q: What is the difference between `CMD` and `ENTRYPOINT` in a Dockerfile?**
> `ENTRYPOINT` is the fixed executable that always runs. `CMD` provides default arguments that can be overridden at `docker run` time. For Spring Boot: use `ENTRYPOINT ["java", "-jar", "app.jar"]` — you never want to override the executable.

**Q: How do you pass different config to the same image in dev vs prod?**
> Environment variables. The image is identical. `SPRING_PROFILES_ACTIVE=prod` activates the prod profile. `SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/users` overrides the datasource. The 12-factor app principle: config in environment, not in code.

**Q: `depends_on` doesn't guarantee the app inside is ready. How do you solve this?**
> Add a `healthcheck` to the dependency that polls `/actuator/health`. Then use `depends_on: condition: service_healthy`. Spring Boot Actuator makes this trivial — every service already exposes `/actuator/health`.

**Q: How do microservices find each other inside Docker Compose?**
> Docker creates an internal DNS. Every service's container name is resolvable as a hostname within the shared network. `user-service` reaches `kafka` at `kafka:9092`, not `localhost:9092`. This is why we override `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` in Docker Compose env vars.
