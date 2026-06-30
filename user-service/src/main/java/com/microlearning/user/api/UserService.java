package com.microlearning.user.api;

import com.microlearning.user.domain.User;
import com.microlearning.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository repo;

    public UserService(UserRepository repo) { this.repo = repo; }

    @Transactional
    public UserResponse createUser(CreateUserRequest req) {
        if (repo.existsByEmail(req.getEmail()))
            throw new IllegalArgumentException("Email already registered: " + req.getEmail());

        User saved = repo.save(User.builder().name(req.getName()).email(req.getEmail()).build());
        log.info("[user-service] Created user id={}", saved.getId());

        // Phase 4: publish UserCreated event to Kafka here
        // kafkaTemplate.send("user-created", new UserCreatedEvent(saved.getId(), saved.getEmail(), saved.getName()));

        return toResponse(saved);
    }

    public UserResponse getUserById(Long id) {
        return repo.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    public List<UserResponse> getAllUsers() {
        return repo.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    private UserResponse toResponse(User u) {
        return UserResponse.builder()
                .id(u.getId()).name(u.getName())
                .email(u.getEmail()).createdAt(u.getCreatedAt())
                .build();
    }
}
