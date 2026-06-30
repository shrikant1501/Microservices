package com.microlearning.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * LoggingFilter — logs every request/response passing through the gateway.
 *
 * PRE-filter:  logs method + path + correlation ID before forwarding
 * POST-filter: logs status code + time taken after response returns
 *
 * In production, this feeds into a centralized log aggregator (ELK / CloudWatch).
 * Phase 11 adds structured JSON logging with MDC for the correlation ID.
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);

        log.info("[gateway] → {} {} correlationId={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                correlationId);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long duration = System.currentTimeMillis() - startTime;
            log.info("[gateway] ← {} {} {}ms correlationId={}",
                    exchange.getResponse().getStatusCode(),
                    exchange.getRequest().getPath(),
                    duration,
                    correlationId);
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // Run after CorrelationIdFilter
    }
}
