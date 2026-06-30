package com.microlearning.todo.client;

import com.microlearning.todo.filter.CorrelationIdMdcFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * FeignAuthInterceptor — Phase 10 + Phase 11: header propagation on every Feign call.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHAT THIS PROPAGATES
 * ═══════════════════════════════════════════════════════════════
 *
 * When todo-service calls user-service via Feign:
 *   Client → Gateway → todo-service → [Feign] → user-service
 *
 * Phase 10 (Auth): Gateway injected X-User-Id / X-User-Roles.
 *   → This interceptor forwards them so user-service knows who the caller is.
 *
 * Phase 11 (Observability): Gateway injected X-Correlation-Id.
 *   → This interceptor forwards it so user-service logs include the same ID.
 *   → Without this, user-service logs would have NO correlation ID,
 *     breaking the ability to trace a request across services.
 *
 * DESIGN PATTERN: One Feign interceptor that propagates all context headers.
 *   Alternative: Separate interceptors per concern (auth vs tracing).
 *   Our approach is simpler; either is valid.
 *
 * NOTE ON THREAD SAFETY:
 *   RequestContextHolder.getRequestAttributes() uses a ThreadLocal internally.
 *   This works in servlet (thread-per-request) model.
 *   For WebFlux (reactive), you'd need Reactor Context instead.
 */
@Component
public class FeignAuthInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FeignAuthInterceptor.class);

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();

            // ─── Phase 10: Auth headers ────────────────────────────────────
            String userId = request.getHeader("X-User-Id");
            String roles  = request.getHeader("X-User-Roles");
            if (userId != null) {
                template.header("X-User-Id", userId);
                log.debug("[feign-interceptor] Propagating X-User-Id={}", userId);
            }
            if (roles != null) {
                template.header("X-User-Roles", roles);
            }

            // ─── Phase 11: Correlation ID ──────────────────────────────────
            // Read from request attribute (set by CorrelationIdMdcFilter)
            // Propagating this ensures user-service logs carry the same ID.
            Object correlationId = request.getAttribute(CorrelationIdMdcFilter.REQUEST_ATTR_KEY);
            if (correlationId != null) {
                template.header(CorrelationIdMdcFilter.CORRELATION_ID_HEADER,
                        correlationId.toString());
                log.debug("[feign-interceptor] Propagating X-Correlation-Id={}", correlationId);
            }
        }
    }
}
