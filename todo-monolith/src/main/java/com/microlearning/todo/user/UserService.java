package com.microlearning.todo.user;

import com.microlearning.todo.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * UserService — business logic for the User domain.
 *
 * ARCHITECTURE NOTE — THE HIDDEN COUPLING:
 * UserService directly injects NotificationService.
 * This is a perfectly normal Spring dependency in a monolith.
 *
 * But look at the consequence:
 * 1. If NotificationService is slow (email server down), createUser() blocks.
 * 2. If NotificationService throws an uncaught exception, user creation fails.
 * 3. You CANNOT deploy UserService changes without testing NotificationService.
 *
 * In microservices, we solve this with async messaging (Kafka):
 * UserService publishes a "UserCreated" event, NotificationService
 * consumes it independently. We will replace this in Phase 4.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public UserService(UserRepository userRepository, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .build();

        User saved = userRepository.save(user);
        log.info("User created with id={}", saved.getId());

        notificationService.sendWelcomeEmail(saved.getEmail(), saved.getName());

        return toResponse(saved);
    }

    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        return toResponse(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
