# Phase 10 — Security & JWT in Microservices

> **80/20 Focus:** How JWT works end-to-end across services, what the Gateway does vs what each service does, and the 6 questions interviewers ask on this topic at senior level.

---

## 1. The Problem

In a monolith, Spring Security protects all endpoints in one place. One `SecurityConfig`, one session store, one `@PreAuthorize`.

In microservices:

```
Client → API Gateway → user-service
                    → todo-service
                    → notification-service

QUESTION: Where does authentication happen?
          Does every service verify the JWT?
          Does only the gateway verify it?
          How does todo-service know WHO the current user is?
```

The naive answer — "every service validates the JWT" — creates:
- Duplicate validation logic in every service
- Every service needs the JWT secret or JWKS endpoint
- Performance overhead (signature verification on every request, every hop)
- Management complexity when rotating secrets

The correct answer — **gateway validates, services trust propagated claims**.

---

## 2. JWT Fundamentals (What Interviewers Assume You Know)

```
JWT = Header.Payload.Signature

Header:  { "alg": "HS256", "typ": "JWT" }
Payload: { "sub": "42", "email": "alice@test.com",
           "roles": ["USER"], "iat": 1700000000, "exp": 1700003600 }
Signature: HMACSHA256(base64(header) + "." + base64(payload), secret)
```

**Three things a JWT does:**
1. **Identity** — who is the user (`sub` = subject = userId)
2. **Claims** — what they are allowed (`roles`, `email`, custom claims)
3. **Integrity** — signature proves nobody tampered with the payload

**Stateless** — the server stores NO session. Every request is self-contained.

**Key difference from sessions:**

| | JWT (stateless) | Session (stateful) |
|---|---|---|
| Storage | Client (browser/mobile) | Server (Redis/DB) |
| Scalability | Any instance can verify | All instances need shared session store |
| Revocation | Hard (token valid until expiry) | Easy (delete session) |
| Microservices fit | Excellent | Requires shared session infrastructure |

---

## 3. The Architecture — Who Does What

```
                         ┌─────────────────────────────────┐
CLIENT                   │        API GATEWAY  :8080        │
sends JWT in:            │                                  │
Authorization:           │  JwtAuthenticationFilter         │
  Bearer <token>   ────► │  1. Extract JWT from header      │
                         │  2. Validate signature + expiry  │
                         │  3. Extract claims from payload  │
                         │  4. Inject as headers:           │
                         │     X-User-Id: 42                │
                         │     X-User-Email: alice@test.com │
                         │     X-User-Roles: USER           │
                         │  5. REMOVE the Authorization     │
                         │     header (services don't need  │
                         │     the raw JWT)                 │
                         └────────────┬────────────────────┘
                                      │  (trusted internal call)
                         ┌────────────▼────────────────────┐
                         │  todo-service                    │
                         │                                  │
                         │  @RequestHeader("X-User-Id")     │
                         │  Long userId                     │
                         │                                  │
                         │  No JWT parsing. No secret.      │
                         │  Trusts the gateway's headers.   │
                         └─────────────────────────────────┘
```

**The contract:**
- **Gateway** = authentication (verifies WHO you are)
- **Each service** = authorization (verifies WHAT you can do)

---

## 4. Why Services Trust the Gateway's Headers

> **"Isn't it a security risk for a service to trust `X-User-Id` header? What if an attacker sets it directly?"**

This is the most common security gotcha question in microservices interviews.

**Answer:** Services are **not on the public internet**. They live in a private network (VPC/Kubernetes cluster). Direct access from outside is blocked by the network layer. Only the gateway's port (8080) is publicly exposed.

```
Internet → [Firewall / Security Group] → API Gateway :8080 (PUBLIC)
                                       → user-service :8081 (PRIVATE — internal only)
                                       → todo-service :8082 (PRIVATE — internal only)

An attacker CANNOT reach todo-service directly from the internet.
Therefore, any request arriving at todo-service with X-User-Id already
passed through the gateway — which already validated the JWT.
```

If internal network security is a concern (e.g., a compromised internal service), use **mTLS** (mutual TLS) between services — each service authenticates the caller with a client certificate.

---

## 5. The Four Security Layers

```
Layer 1 — Network:     Only gateway port is public. Services are internal-only.
Layer 2 — Gateway:     JWT signature + expiry validation. Extract and inject claims.
Layer 3 — Service:     Authorization — does this user have permission for this resource?
Layer 4 — Data:        Row-level security — user can only see THEIR todos.
```

**Layer 3 example in todo-service:**

```java
@GetMapping
public ResponseEntity<List<TodoResponse>> getTodosByUser(
        @RequestHeader("X-User-Id") Long requestingUserId,
        @RequestParam Long userId) {

    // AUTHORIZATION: you can only see your own todos
    if (!requestingUserId.equals(userId)) {
        throw new AccessDeniedException("You can only view your own todos");
    }
    return ResponseEntity.ok(todoService.getTodosByUser(userId));
}
```

---

## 6. JWT Filter in the Gateway — The Implementation

```java
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    // In production: load from Config Server or Vault, never hardcode
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        // No token — reject (unless it's a public endpoint like /api/auth/**)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7); // strip "Bearer "

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes()))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // Inject claims as headers for downstream services
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id",    claims.getSubject())
                    .header("X-User-Email", claims.get("email", String.class))
                    .header("X-User-Roles", claims.get("roles", String.class))
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());

        } catch (ExpiredJwtException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        } catch (JwtException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() { return -2; } // Run before CorrelationIdFilter
}
```

---

## 7. Consuming the Headers in Services — The Implementation

```java
// In any service controller — NO JWT library needed
@PostMapping
public ResponseEntity<TodoResponse> createTodo(
        @Valid @RequestBody CreateTodoRequest req,
        @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {

    // Use the authenticated user ID from the gateway, not from the request body
    // This prevents users from creating todos for other users
    if (authenticatedUserId != null) {
        req.setUserId(authenticatedUserId); // override with authenticated ID
    }

    return ResponseEntity.status(HttpStatus.CREATED)
            .body(todoService.createTodo(req));
}
```

---

## 8. Service-to-Service JWT Propagation

When todo-service calls user-service via Feign, should it pass the user's JWT?

```
Client → Gateway (validates JWT) → todo-service → user-service

Does user-service need to know the calling user's identity?
```

**Pattern: Pass X-User-Id header via Feign**

```java
// In a Feign RequestInterceptor — runs on every Feign call
@Component
public class FeignAuthInterceptor implements RequestInterceptor {

    // ThreadLocal holds the current request's user ID
    // Set by a Filter that reads the X-User-Id header
    @Override
    public void apply(RequestTemplate template) {
        // Propagate the user identity to downstream services
        String userId = UserContextHolder.getCurrentUserId();
        if (userId != null) {
            template.header("X-User-Id", userId);
        }
    }
}
```

This is **header propagation** — the user context flows through the entire call chain.

---

## 9. OAuth2 / OpenID Connect — What Interviewers Expect at Senior Level

For production systems, you don't implement JWT issuance yourself. You use:

- **Keycloak** (open-source, self-hosted)
- **Auth0** (SaaS)
- **AWS Cognito** (cloud-native)
- **Okta** (enterprise)

These act as the **Authorization Server** (issues JWTs) and **JWKS endpoint** (provides public keys for verification).

```
OAUTH2 FLOW:
  Client → Authorization Server (login)
  Auth Server → issues JWT (access token)
  Client → Gateway with JWT
  Gateway → JWKS endpoint: "is this token valid?"
  JWKS → returns public key → Gateway verifies signature
  Gateway → extracts claims → forwards to services

BENEFIT: Gateway never holds a secret key — only the public key.
         Secret key stays exclusively on the Authorization Server.
         Rotating secrets doesn't require gateway redeployment.
```

> **Interview Q: "What is the difference between authentication and authorization in microservices?"**
> Authentication = proving identity (who are you?). Done at the gateway using JWT. Authorization = enforcing permissions (what can you do?). Done in each service — the service checks if the authenticated user (from X-User-Id/X-User-Roles) has permission for the specific operation. This separation means a compromised service can't impersonate another user (authentication is handled at a trusted layer), but each service independently enforces its own access rules.

---

## 10. Key Interview Questions

**Q1. How does authentication work in a microservices architecture?**
> The API Gateway is the authentication boundary. Every inbound request carries a JWT in the Authorization header. The Gateway validates the signature and expiry using the JWT secret (or JWKS public key). If valid, it extracts the claims (userId, email, roles) and injects them as HTTP headers (X-User-Id, X-User-Email, X-User-Roles). These headers are passed to downstream services. Individual services perform authorization — checking if the extracted user has permission for the requested operation. Services never see or parse the raw JWT.

**Q2. Why does the Gateway remove the Authorization header before forwarding to services?**
> Services don't need the raw JWT — they only need the extracted claims. Keeping the JWT in the request adds unnecessary payload size. More importantly, it prevents services from accidentally depending on JWT parsing. If services parsed the JWT themselves, they'd each need the secret — creating secret sprawl and making key rotation harder. The gateway is the single place that knows the secret; services only know the claim headers.

**Q3. What happens if the JWT is expired?**
> The Gateway catches `ExpiredJwtException` during parsing and immediately returns HTTP 401 Unauthorized — the request never reaches a downstream service. The client must re-authenticate (typically by using a refresh token to get a new access token). In Spring Cloud Gateway, this is handled in the GlobalFilter. Adding a public endpoint whitelist (e.g., `/api/auth/**`) allows login and refresh token endpoints to bypass JWT validation.

**Q4. How do you handle service-to-service calls — does the internal Feign call also carry a JWT?**
> It depends on whether the downstream service needs user context. If user-service needs to know WHO is calling (for audit or authorization), the calling service propagates the X-User-Id header via a Feign `RequestInterceptor`. If it's a pure internal operation that doesn't need user context (e.g., a background job calling an internal API), a service account token or mTLS is used instead. Passing the raw user JWT between services is generally avoided — headers are cleaner and don't leak token expiry concerns to internal services.

**Q5. What is the difference between JWT and opaque tokens?**
> A JWT is self-contained — the recipient can validate it locally without calling any external service, because the payload contains all claims and the signature proves integrity. An opaque token is a random string with no embedded data — the recipient must call the token issuer's introspection endpoint to validate it and get the user's claims. JWTs have lower latency (no network call to validate) but are harder to revoke (valid until expiry). Opaque tokens are easily revoked (just delete from the token store) but add latency (network call per request). Most modern microservices use JWTs with short expiry (15 minutes) + refresh tokens.

---

## 11. Tricky Interview Questions

**Q. How do you revoke a JWT before it expires?**
> JWTs can't be truly revoked — the signature is valid until expiry. Solutions: (1) Short expiry (15 minutes) + refresh tokens (easier to revoke). (2) Token blocklist — maintain a Redis cache of revoked JWTs; Gateway checks this on every request (adds latency, negates statelessness). (3) Change the JWT secret — immediately invalidates all tokens but forces all users to re-login. (4) Switch to opaque tokens for sensitive operations where immediate revocation is required. The industry standard: short-lived access tokens + long-lived refresh tokens, with refresh tokens stored server-side (revocable).

**Q. A user's role changes from USER to ADMIN. They have a valid JWT with role=USER. How do you handle this?**
> The role in the JWT is stale until the token expires. Solutions: (1) Short expiry tokens — the user re-authenticates within 15 minutes and gets a new JWT with the updated role. (2) Force re-login on role change — invalidate all sessions/refresh tokens for this user. (3) Token version — include a version number in the JWT; store the current version in a fast cache (Redis); Gateway rejects tokens with an old version. Solution 3 adds a Redis lookup per request but enables immediate role propagation.

---

## 12. Quick Revision Cheat Sheet

```
JWT STRUCTURE
 └─ Header.Payload.Signature (base64 encoded, dot-separated)
 └─ Payload: sub (userId), roles, email, iat, exp
 └─ Signature: HMAC(header+payload, secret) — proves tampering

MICROSERVICES AUTH FLOW
 └─ Client sends: Authorization: Bearer <jwt>
 └─ Gateway: validates signature + expiry
 └─ Gateway: extracts claims → injects as headers
    X-User-Id, X-User-Email, X-User-Roles
 └─ Services: read headers, perform authorization

GATEWAY = Authentication (who are you?)
SERVICE = Authorization  (what can you do?)

WHY SERVICES TRUST HEADERS
 └─ Services are NOT on the public internet (private network)
 └─ Only gateway port is publicly exposed
 └─ mTLS for additional service-to-service trust

JWT vs SESSION
 └─ JWT: stateless, client-stored, any instance validates
 └─ Session: stateful, server-stored, needs shared store

REVOCATION CHALLENGE
 └─ JWTs valid until expiry — hard to revoke immediately
 └─ Solutions: short expiry (15m) + refresh tokens
              OR token blocklist in Redis
              OR token versioning

OAUTH2 / OIDC (production)
 └─ Don't implement JWT issuance yourself
 └─ Use Keycloak / Auth0 / Cognito
 └─ JWKS endpoint: Gateway validates via public key (no secret on Gateway)

SERVICE-TO-SERVICE
 └─ Propagate X-User-Id via Feign RequestInterceptor
 └─ Or use service account tokens / mTLS
 └─ Never pass raw user JWT between internal services
```
