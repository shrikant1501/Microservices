package com.microlearning.todo.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TodoController — Phase 10: header-based authorization.
 *
 * The gateway already validated the JWT and injected:
 *   X-User-Id    → the authenticated user's ID
 *   X-User-Roles → the authenticated user's roles
 *
 * This service reads those headers — NO JWT parsing, NO Spring Security dependency.
 *
 * AUTHORIZATION enforced here:
 *   - createTodo: override userId with the authenticated user's ID
 *     (prevents "create a todo for user 99" when logged in as user 1)
 *   - getTodosByUser: can only fetch your own todos
 *
 * If X-User-Id header is absent (direct call bypassing gateway in dev):
 *   required=false means the parameter is null → service proceeds without auth enforcement
 *   In production: network rules prevent direct access, so this is acceptable.
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService service;
    public TodoController(TodoService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<TodoResponse> create(
            @Valid @RequestBody CreateTodoRequest req,
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {

        // AUTHORIZATION: force the todo to belong to the authenticated user
        // Prevents: POST /api/todos { "userId": 99 } while logged in as user 1
        if (authenticatedUserId != null) {
            req.setUserId(authenticatedUserId);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTodo(req));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<TodoResponse> complete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {
        // Could add: verify that this todo belongs to the authenticated user
        return ResponseEntity.ok(service.completeTodo(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TodoResponse> getById(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {
        return ResponseEntity.ok(service.getTodoById(id));
    }

    @GetMapping
    public ResponseEntity<List<TodoResponse>> getByUser(
            @RequestParam Long userId,
            @RequestHeader(value = "X-User-Id", required = false) Long authenticatedUserId) {

        // AUTHORIZATION: you can only list your own todos
        if (authenticatedUserId != null && !authenticatedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.getTodosByUser(userId));
    }
}
