package com.microlearning.todo.api;

import com.microlearning.todo.client.UserResponse;
import com.microlearning.todo.client.UserServiceClient;
import com.microlearning.todo.domain.Todo;
import com.microlearning.todo.domain.TodoRepository;
import feign.FeignException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TodoService — Phase 8: Full Resilience4j integration.
 *
 * ═══════════════════════════════════════════════════════════════
 * THE EVOLUTION OF THE USER-SERVICE CALL
 * ═══════════════════════════════════════════════════════════════
 *
 * Phase 1 (Monolith):
 *   userRepository.findById(id)        ← in-process, cannot fail
 *
 * Phase 3 (Raw Feign):
 *   userServiceClient.getUserById(id)  ← HTTP, can fail, threads block
 *
 * Phase 8 (Resilient Feign):
 *   @Bulkhead     → cap concurrent calls to user-service at 10
 *   @Retry        → retry 3 times on transient failures
 *   @CircuitBreaker → after 5+ failures, open circuit, return fallback
 *
 * EXECUTION ORDER (outermost → innermost):
 *   Bulkhead → Retry → CircuitBreaker → actual HTTP call
 *
 * So a single call attempt goes:
 *   1. Bulkhead: is a slot available? (if not → BulkheadFullException)
 *   2. CircuitBreaker: is circuit CLOSED? (if OPEN → CallNotPermittedException)
 *   3. HTTP call to user-service
 *   4. If fails → Retry retries from step 2 (up to 3 times)
 *   5. If all retries exhausted → fallbackMethod is called
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);
    private static final String USER_SERVICE_CB = "user-service";

    private final TodoRepository todoRepo;
    private final UserServiceClient userClient;

    public TodoService(TodoRepository todoRepo, UserServiceClient userClient) {
        this.todoRepo = todoRepo;
        this.userClient = userClient;
    }

    /**
     * createTodo with full resilience stack.
     *
     * If user-service is down:
     *   - Retry attempts 3 times (1s apart)
     *   - After all retries fail, Circuit Breaker records the failure
     *   - Once CB opens, createTodo returns 503 via fallback
     *
     * NOTE: For create operations we do NOT silently succeed in the fallback.
     * The fallback throws an exception — you cannot create a todo for an unknown user.
     * Contrast with getTodo (read) where a stale/partial response is acceptable.
     */
    @Transactional
    @CircuitBreaker(name = USER_SERVICE_CB, fallbackMethod = "createTodoFallback")
    @Retry(name = USER_SERVICE_CB)
    @Bulkhead(name = USER_SERVICE_CB, type = Bulkhead.Type.SEMAPHORE)
    public TodoResponse createTodo(CreateTodoRequest req) {
        UserResponse user;
        try {
            user = userClient.getUserById(req.getUserId());
        } catch (FeignException.NotFound e) {
            throw new RuntimeException("User not found: " + req.getUserId());
        }

        Todo todo = Todo.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .userId(user.getId())
                .userName(user.getName())
                .build();

        Todo saved = todoRepo.save(todo);
        log.info("[todo-service] Created todo id={} for userId={}", saved.getId(), saved.getUserId());
        return toResponse(saved);
    }

    /**
     * Fallback for createTodo.
     *
     * Called when:
     *   - Circuit Breaker is OPEN (user-service down)
     *   - All retries exhausted
     *   - Bulkhead full
     *
     * For writes: throw a clear error — never silently succeed.
     * The client gets a 503 with a meaningful message.
     */
    public TodoResponse createTodoFallback(CreateTodoRequest req, Throwable ex) {
        log.warn("[todo-service] createTodo fallback triggered. userId={}, reason={}",
                req.getUserId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        throw new RuntimeException(
                "user-service is currently unavailable. Cannot create todo. Please retry later.");
    }

    /**
     * getTodoById — read operation, accepts degraded response.
     *
     * If user-service is needed to enrich the response and is down,
     * we return the todo with userName from the stored snapshot.
     * No call to user-service here — just local DB read.
     * This is why we stored the userName snapshot in Phase 3.
     */
    public TodoResponse getTodoById(Long id) {
        Todo todo = todoRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Todo not found: " + id));
        return toResponse(todo);
    }

    @Transactional
    public TodoResponse completeTodo(Long todoId) {
        Todo todo = todoRepo.findById(todoId)
                .orElseThrow(() -> new RuntimeException("Todo not found: " + todoId));
        todo.setCompleted(true);
        todo.setCompletedAt(LocalDateTime.now());
        log.info("[todo-service] Completed todo id={}", todoId);
        return toResponse(todoRepo.save(todo));
    }

    public List<TodoResponse> getTodosByUser(Long userId) {
        return todoRepo.findByUserId(userId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private TodoResponse toResponse(Todo t) {
        return TodoResponse.builder()
                .id(t.getId()).title(t.getTitle()).description(t.getDescription())
                .completed(t.isCompleted()).userId(t.getUserId()).userName(t.getUserName())
                .createdAt(t.getCreatedAt()).completedAt(t.getCompletedAt())
                .build();
    }
}
