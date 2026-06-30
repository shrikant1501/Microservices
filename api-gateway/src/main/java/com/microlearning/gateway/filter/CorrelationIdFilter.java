package com.microlearning.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * CorrelationIdFilter — GlobalFilter applied to EVERY request.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHY THIS MATTERS (Phase 11 preview)
 * ═══════════════════════════════════════════════════════════════
 *
 * Distributed tracing problem:
 *   A single client request hits the gateway, which calls user-service,
 *   which triggers a Kafka event consumed by notification-service.
 *   When something fails, you have logs in 3 different services.
 *   Without a correlation ID, finding the related log entries is impossible.
 *
 * With X-Correlation-Id:
 *   Gateway generates: "X-Correlation-Id: abc-123"
 *   Forwards it to user-service → user-service logs "abc-123: user created"
 *   user-service forwards to todo-service → "abc-123: todo created"
 *   notification-service extracts from event → "abc-123: email sent"
 *
 *   To debug any request: grep all logs for "abc-123" → full trace.
 *
 * FLOW:
 * 1. If client already provides X-Correlation-Id (e.g., from a mobile app), use it.
 * 2. Otherwise, generate a new UUID.
 * 3. Add to the forwarded request (downstream services receive it).
 * 4. Add to the response (client can log it for support tickets).
 *
 * Ordered.HIGHEST_PRECEDENCE → runs before all other filters.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalId = correlationId;
        log.debug("[gateway] {} {} correlationId={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                finalId);

        // Inject into forwarded request AND response eagerly (before chain, so it's
        // always present even if downstream returns an error)
        exchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, finalId);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, finalId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // First filter in the chain
    }
}
