package com.microlearning.user.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * User — Aggregate Root of the User Bounded Context.
 *
 * MICROSERVICES NOTE:
 * This entity is PRIVATE to user-service. No other service imports
 * or references this class. Other services that need user data
 * call GET /api/users/{id} and receive a UserResponse DTO.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Email @NotBlank
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public User() {}

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    // Getters
    public Long getId()                  { return id; }
    public String getName()              { return name; }
    public String getEmail()             { return email; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    // Setters
    public void setName(String name)   { this.name = name; }
    public void setEmail(String email) { this.email = email; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name, email;
        public Builder name(String n)  { this.name = n;  return this; }
        public Builder email(String e) { this.email = e; return this; }
        public User build() {
            User u = new User();
            u.name = this.name;
            u.email = this.email;
            return u;
        }
    }
}
