package com.microlearning.todo.common;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler — todo-service.
 *
 * MICROSERVICES NOTE:
 * Notice the FeignException handler. In the monolith, if UserRepository
 * threw an exception it was a standard RuntimeException. Now that we
 * call user-service over HTTP, we must handle Feign-specific exceptions:
 *
 *   FeignException.NotFound (404)     → user does not exist
 *   FeignException.ServiceUnavailable → user-service is down
 *   FeignException (catch-all)        → any other HTTP error from user-service
 *
 * Phase 8 replaces the FeignException handler with a Circuit Breaker fallback.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
        log.error("[todo-service] RuntimeException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage(), 404, LocalDateTime.now()));
    }

    /**
     * Handles errors propagated FROM user-service via Feign.
     *
     * Example: user-service returns 503 (down) → Feign throws FeignException
     * → todo-service returns 503 to the client with a meaningful message.
     *
     * WITHOUT Circuit Breaker: every 503 from user-service causes todo-service
     * to block a thread for up to 5 seconds (read-timeout), then throw this.
     *
     * WITH Circuit Breaker (Phase 8): after 5 consecutive failures, the circuit
     * opens and the fallback is returned immediately — no thread blocking.
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeignException(FeignException ex) {
        log.error("[todo-service] Feign call to user-service failed: status={} msg={}",
                ex.status(), ex.getMessage());
        int status = ex.status() > 0 ? ex.status() : 503;
        return ResponseEntity.status(status)
                .body(new ErrorResponse("Upstream service error: " + ex.getMessage(), status, LocalDateTime.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(e ->
                errors.put(((FieldError) e).getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Validation failed: " + errors, 400, LocalDateTime.now()));
    }

    public record ErrorResponse(String message, int status, LocalDateTime timestamp) {}
}
