package com.microlearning.user.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CorrelationIdMdcFilter — Phase 11: Observability.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHAT THIS DOES
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. Reads X-Correlation-Id header injected by the API Gateway
 * 2. Puts it in SLF4J MDC so EVERY log line in this request includes it
 * 3. Clears MDC after the request (prevents thread pool contamination)
 * 4. Echoes the correlation ID back in the response header
 *
 * BEFORE (without this filter):
 *   INFO UserController - Processing GET /api/users/42
 *
 * AFTER (with this filter):
 *   INFO [abc-123] UserController - Processing GET /api/users/42
 *
 * The "[abc-123]" is the correlationId injected by the Gateway.
 * Grep all service logs for "abc-123" → see the complete request trail.
 *
 * WHY OncePerRequestFilter:
 *   Guarantees this runs exactly once per HTTP request, not once per
 *   filter chain dispatch (e.g., forward dispatches would run it twice
 *   without this guarantee).
 *
 * ORDER: HIGHEST_PRECEDENCE — must run before any business logic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdMdcFilter.class);
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);

        // If no correlation ID in header, this is a direct call (not via Gateway).
        // Generate one so logs are still traceable within this service.
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "direct-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        try {
            // Put in MDC — Logback appends this to every log line for this thread
            MDC.put(MDC_KEY, correlationId);

            // Echo back in response so clients can reference it in support tickets
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            log.debug("[correlationId-filter] correlationId={} path={}", correlationId, request.getRequestURI());

            filterChain.doFilter(request, response);

        } finally {
            // CRITICAL: Always clear MDC. Thread pools reuse threads.
            // Without this, the next request on this thread would see
            // the previous request's correlationId in its logs.
            MDC.remove(MDC_KEY);
        }
    }
}
