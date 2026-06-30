package com.microlearning.todo.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService service;
    public TodoController(TodoService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<TodoResponse> create(@Valid @RequestBody CreateTodoRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTodo(req));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<TodoResponse> complete(@PathVariable Long id) {
        return ResponseEntity.ok(service.completeTodo(id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TodoResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getTodoById(id));
    }

    @GetMapping
    public ResponseEntity<List<TodoResponse>> getByUser(@RequestParam Long userId) {
        return ResponseEntity.ok(service.getTodosByUser(userId));
    }
}
