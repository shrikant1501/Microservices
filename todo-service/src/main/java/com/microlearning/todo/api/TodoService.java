package com.microlearning.todo.api;

import com.microlearning.todo.client.UserResponse;
import com.microlearning.todo.client.UserServiceClient;
import com.microlearning.todo.domain.Todo;
import com.microlearning.todo.domain.TodoRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TodoService — Phase 3 implementation.
 *
 * THE KEY DIFFERENCE FROM THE MONOLITH:
 * ─────────────────────────────────────
 * Monolith:   userRepository.findById(userId)   [in-process, ACID, cannot fail]
 * Phase 3:    userServiceClient.getUserById(id) [HTTP, network, CAN fail]
 *
 * This single change introduces ALL distributed systems problems:
 *   → Latency      (network is slower than RAM)
 *   → Failure      (user-service may be down)
 *   → Timeouts     (user-service may be slow)
 *   → Retries      (should we retry on failure?)
 *   → Consistency  (user name in todos can become stale)
 *
 * Phase 8 (Resilience4j) addresses the failure/timeout/retry problems.
 * The consistency trade-off is a permanent architectural decision.
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private final TodoRepository todoRepo;
    private final UserServiceClient userClient;

    public TodoService(TodoRepository todoRepo, UserServiceClient userClient) {
        this.todoRepo = todoRepo;
        this.userClient = userClient;
    }

    /**
     * Creates a Todo after validating the user exists via user-service.
     *
     * COMMUNICATION FLOW:
     * 1. Receive CreateTodoRequest (has userId)
     * 2. Call user-service: GET /api/users/{userId}
     *    → If 404: throw RuntimeException ("User not found")
     *    → If down: FeignException → HTTP 503 to client
     * 3. Create Todo with userId + userName snapshot from step 2
     * 4. Save to todos table (THIS service's own DB)
     * 5. Return TodoResponse
     *
     * NOTE: Steps 2 and 4 are NOT in the same transaction.
     * There is no distributed ACID here. If step 4 fails after step 2 succeeds,
     * user-service has "succeeded" but no todo was created.
     * Phase 9 (Saga pattern) addresses distributed transaction consistency.
     */
    @Transactional
    public TodoResponse createTodo(CreateTodoRequest req) {
        // ─── INTER-SERVICE CALL ──────────────────────────────────────────
        // This is an HTTP call over the network. It can fail.
        // Phase 8 wraps this with a Circuit Breaker.
        UserResponse user;
        try {
            user = userClient.getUserById(req.getUserId());
        } catch (FeignException.NotFound e) {
            throw new RuntimeException("User not found: " + req.getUserId());
        }
        // ─────────────────────────────────────────────────────────────────

        Todo todo = Todo.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .userId(user.getId())
                .userName(user.getName())   // ← snapshot stored here
                .build();

        Todo saved = todoRepo.save(todo);
        log.info("[todo-service] Created todo id={} for userId={}", saved.getId(), saved.getUserId());

        // Phase 4: publish TodoCreated event to Kafka here

        return toResponse(saved);
    }

    @Transactional
    public TodoResponse completeTodo(Long todoId) {
        Todo todo = todoRepo.findById(todoId)
                .orElseThrow(() -> new RuntimeException("Todo not found: " + todoId));
        todo.setCompleted(true);
        todo.setCompletedAt(LocalDateTime.now());
        Todo saved = todoRepo.save(todo);

        // Phase 4: publish TodoCompleted event to Kafka here
        // → notification-service consumes it and sends an email

        log.info("[todo-service] Completed todo id={}", todoId);
        return toResponse(saved);
    }

    public TodoResponse getTodoById(Long id) {
        Todo todo = todoRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Todo not found: " + id));
        return toResponse(todo);
    }

    public List<TodoResponse> getTodosByUser(Long userId) {
        return todoRepo.findByUserId(userId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    private TodoResponse toResponse(Todo t) {
        return TodoResponse.builder()
                .id(t.getId()).title(t.getTitle()).description(t.getDescription())
                .completed(t.isCompleted()).userId(t.getUserId()).userName(t.getUserName())
                .createdAt(t.getCreatedAt()).completedAt(t.getCompletedAt())
                .build();
    }
}
