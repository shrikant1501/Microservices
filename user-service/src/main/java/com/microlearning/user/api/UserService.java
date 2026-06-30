package com.microlearning.user.api;

import com.microlearning.user.domain.User;
import com.microlearning.user.domain.UserRepository;
import com.microlearning.user.event.UserCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repo;
    private final KafkaTemplate<String, Object> kafka;

    public UserService(UserRepository repo, KafkaTemplate<String, Object> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest req) {
        if (repo.existsByEmail(req.getEmail()))
            throw new IllegalArgumentException("Email already registered: " + req.getEmail());

        User saved = repo.save(User.builder().name(req.getName()).email(req.getEmail()).build());

        // ─── PHASE 4 CHANGE ────────────────────────────────────────────────
        // BEFORE (monolith / Phase 3): notificationService.sendWelcomeEmail()
        //   → synchronous, coupled, failure cancels user registration
        //
        // AFTER (Phase 4): publish event to Kafka
        //   → async, decoupled, notification failure has ZERO impact on user creation
        //   → any number of consumers can react (notification, analytics, fraud, ...)
        //   → partition key = userId → all events for this user are ordered
        //
        // OUTBOX NOTE FOR PRODUCTION:
        //   Save an OutboxEvent in the SAME @Transactional block as the User save.
        //   A separate poller reads the outbox table and publishes to Kafka.
        //   This guarantees the DB and Kafka are always consistent.
        //   Here we publish directly (sufficient for learning).
        // ────────────────────────────────────────────────────────────────────
        var event = new UserCreatedEvent(saved.getId(), saved.getEmail(), saved.getName());
        kafka.send("user-created", String.valueOf(saved.getId()), event);
        log.info("[user-service] Published UserCreatedEvent for userId={}", saved.getId());

        return toResponse(saved);
    }

    public UserResponse getUserById(Long id) {
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    public List<UserResponse> getAllUsers() {
        return repo.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    private UserResponse toResponse(User u) {
        return UserResponse.builder().id(u.getId()).name(u.getName())
                .email(u.getEmail()).createdAt(u.getCreatedAt()).build();
    }
}
