package com.microlearning.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * JwtAuthenticationFilter — Phase 10.
 *
 * Runs BEFORE every route is forwarded. Validates the JWT and injects
 * user claims as headers so downstream services never touch the raw token.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHAT THIS FILTER DOES
 * ═══════════════════════════════════════════════════════════════
 * 1. Check if path is public (whitelist) → skip validation
 * 2. Extract "Authorization: Bearer <token>" header
 * 3. Validate: signature correct + not expired
 * 4. Extract claims: sub (userId), email, roles
 * 5. Inject as headers for downstream services:
 *      X-User-Id, X-User-Email, X-User-Roles
 * 6. REMOVE the Authorization header (services don't need raw JWT)
 * 7. Forward the mutated request
 *
 * WHAT DOWNSTREAM SERVICES DO:
 *   @RequestHeader("X-User-Id") Long userId
 *   → No JWT library. No secret. Trusts the gateway.
 *
 * ORDER: HIGHEST_PRECEDENCE + 1 (runs just AFTER CorrelationIdFilter which is HIGHEST_PRECEDENCE)
 * This ensures Correlation ID is already set before JWT short-circuits, so 401 responses
 * also carry the X-Correlation-Id header for traceability.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    public static final String HEADER_USER_ID    = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.public-paths:/api/auth/**,/actuator/**}")
    private String[] publicPaths;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // ─── Step 1: Public endpoint whitelist ────────────────────────────
        if (isPublicPath(path)) {
            log.debug("[jwt-filter] Public path, skipping validation: {}", path);
            return chain.filter(exchange);
        }

        // ─── Step 2: Extract token ─────────────────────────────────────────
        String authHeader = exchange.getRequest().getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[jwt-filter] Missing or malformed Authorization header for path={}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);

        // ─── Steps 3–6: Validate → extract → inject → strip raw JWT ────────
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(
                            jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String userId = claims.getSubject();
            String email  = claims.get("email",  String.class);
            String roles  = claims.get("roles",  String.class);

            log.debug("[jwt-filter] Token valid for userId={} path={}", userId, path);

            // Mutate request: inject claims as headers, remove raw JWT
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header(HEADER_USER_ID,    userId != null ? userId : "")
                    .header(HEADER_USER_EMAIL, email  != null ? email  : "")
                    .header(HEADER_USER_ROLES, roles  != null ? roles  : "")
                    .headers(h -> h.remove(HttpHeaders.AUTHORIZATION)) // strip raw JWT
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build());

        } catch (ExpiredJwtException e) {
            log.warn("[jwt-filter] Expired JWT for path={}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        } catch (JwtException e) {
            log.warn("[jwt-filter] Invalid JWT for path={}: {}", path, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private boolean isPublicPath(String path) {
        return Arrays.stream(publicPaths)
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    public int getOrder() {
        // HIGHEST_PRECEDENCE = Integer.MIN_VALUE.
        // Using MIN_VALUE - 1 causes integer overflow → wrong ordering.
        // We want to run JUST AFTER CorrelationIdFilter (which is HIGHEST_PRECEDENCE).
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
