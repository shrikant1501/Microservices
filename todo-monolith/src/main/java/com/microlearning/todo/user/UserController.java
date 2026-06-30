package com.microlearning.todo.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * UserController — REST API for User domain.
 *
 * ARCHITECTURE NOTE:
 * Notice this controller lives inside the same JVM as TodoController.
 * A request to /api/users and a request to /api/todos hit the
 * SAME process, SAME thread pool, SAME JVM heap.
 *
 * Problem: If the /api/todos endpoint has a memory leak, it can
 * cause OutOfMemoryError that takes down /api/users too.
 *
 * In microservices: UserService and TodoService run in SEPARATE JVMs.
 * A crash in TodoService has zero impact on UserService.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }
}
