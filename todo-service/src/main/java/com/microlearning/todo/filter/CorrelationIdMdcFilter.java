package com.microlearning.todo.filter;

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
import java.util.UUID;

/**
 * CorrelationIdMdcFilter — Phase 11: Observability.
 *
 * Reads X-Correlation-Id header (injected by API Gateway) → puts in MDC
 * so every log line for this request includes the correlation ID.
 *
 * Also stores the correlationId in request attribute so FeignCorrelationInterceptor
 * can access it when making downstream calls to user-service.
 *
 * See: user-service/filter/CorrelationIdMdcFilter.java for detailed explanation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdMdcFilter.class);
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ATTR_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = "direct-" + UUID.randomUUID().toString().substring(0, 8);
        }

        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            // Store in request attribute — FeignCorrelationInterceptor reads this
            // to propagate the header to user-service calls
            request.setAttribute(REQUEST_ATTR_KEY, correlationId);

            log.debug("[correlationId-filter] correlationId={} path={}", correlationId, request.getRequestURI());

            filterChain.doFilter(request, response);

        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
