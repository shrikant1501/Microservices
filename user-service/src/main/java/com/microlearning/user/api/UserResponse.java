package com.microlearning.user.api;

import java.time.LocalDateTime;

/**
 * UserResponse — the PUBLIC CONTRACT of user-service.
 *
 * CRITICAL MICROSERVICES PRINCIPLE:
 * This DTO is what every other service receives when it calls user-service.
 * The internal User entity can change freely (add fields, rename columns)
 * as long as this response contract remains stable.
 *
 * Versioning this response is how you evolve without breaking consumers:
 *   /api/v1/users/{id} → UserResponse (current)
 *   /api/v2/users/{id} → UserResponseV2 (future, backward-compatible)
 */
public class UserResponse {
    private Long id;
    private String name;
    private String email;
    private LocalDateTime createdAt;

    public UserResponse() {}
    private UserResponse(Builder b) {
        this.id = b.id; this.name = b.name;
        this.email = b.email; this.createdAt = b.createdAt;
    }

    public Long getId()                 { return id; }
    public String getName()             { return name; }
    public String getEmail()            { return email; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private Long id; private String name, email; private LocalDateTime createdAt;
        public Builder id(Long id)             { this.id = id;           return this; }
        public Builder name(String n)          { this.name = n;          return this; }
        public Builder email(String e)         { this.email = e;         return this; }
        public Builder createdAt(LocalDateTime t){ this.createdAt = t;   return this; }
        public UserResponse build()            { return new UserResponse(this); }
    }
}
