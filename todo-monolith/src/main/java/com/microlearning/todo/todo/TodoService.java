package com.microlearning.todo.todo;

import com.microlearning.todo.user.User;
import com.microlearning.todo.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TodoService — business logic for the Todo domain.
 *
 * ARCHITECTURE NOTE — SHARED DATABASE PROBLEM:
 * TodoService directly injects UserRepository. This means:
 * 1. Todo and User modules share the SAME database connection pool.
 * 2. If "users" table is renamed (a User domain concern), TodoService breaks.
 * 3. When we extract into microservices, UserRepository disappears.
 *    TodoService will call: GET http://user-service/api/users/{id}
 *    instead of: userRepository.findById(userId)
 *
 * This cross-domain repository injection is intentionally shown
 * so you can feel the problem before we solve it in Phase 3.
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private final TodoRepository todoRepository;
    private final UserRepository userRepository; // ← CROSS-DOMAIN DEPENDENCY (removed in Phase 3)

    public TodoService(TodoRepository todoRepository, UserRepository userRepository) {
        this.todoRepository = todoRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TodoResponse createTodo(CreateTodoRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found: " + request.getUserId()));

        Todo todo = Todo.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .user(user)
                .completed(false)
                .build();

        Todo saved = todoRepository.save(todo);
        log.info("Todo created with id={} for userId={}", saved.getId(), user.getId());
        return toResponse(saved);
    }

    @Transactional
    public TodoResponse completeTodo(Long todoId) {
        Todo todo = todoRepository.findById(todoId)
                .orElseThrow(() -> new RuntimeException("Todo not found: " + todoId));

        todo.setCompleted(true);
        todo.setCompletedAt(LocalDateTime.now());
        return toResponse(todoRepository.save(todo));
    }

    public List<TodoResponse> getTodosByUser(Long userId) {
        return todoRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public TodoResponse getTodoById(Long id) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Todo not found: " + id));
        return toResponse(todo);
    }

    private TodoResponse toResponse(Todo todo) {
        return TodoResponse.builder()
                .id(todo.getId())
                .title(todo.getTitle())
                .description(todo.getDescription())
                .completed(todo.isCompleted())
                .userId(todo.getUser().getId())
                .userName(todo.getUser().getName())
                .createdAt(todo.getCreatedAt())
                .completedAt(todo.getCompletedAt())
                .build();
    }
}
