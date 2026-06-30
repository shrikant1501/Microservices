# Multi-stage Dockerfile — used by all Spring Boot services.
#
# HOW TO USE:
#   Copy this file into each service directory.
#   The only difference between services is the EXPOSE port.
#
# Build:  docker build -t user-service .
# Run:    docker run -p 8081:8081 user-service
#
# In practice: docker compose build does this for every service automatically.

# ─── Stage 1: Build ──────────────────────────────────────────────────────────
# eclipse-temurin:21-jdk-alpine = OpenJDK 21 (Alpine = minimal Linux ~12MB base)
# This stage is DISCARDED after the build — it never ships to production.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper first — allows Docker to cache the dependency-download layer.
# If only src/ changes, Docker reuses the cached deps layer (much faster rebuilds).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Now copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# JRE only (no compiler, no Maven) → ~200MB vs ~600MB for JDK image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy only the fat JAR from the build stage
COPY --from=builder /app/target/*.jar app.jar

# Document the port (informational — actual binding happens in docker-compose.yml)
EXPOSE 8081

# ENTRYPOINT (not CMD) — this executable cannot be overridden at docker run
# -Djava.security.egd=file:/dev/./urandom — faster startup on Linux (SecureRandom entropy)
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
