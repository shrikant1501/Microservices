package com.microlearning.todo.client;

import java.time.LocalDateTime;

/**
 * UserResponse — todo-service's LOCAL copy of the user-service contract.
 *
 * ═══════════════════════════════════════════════════════════════
 * IMPORTANT ARCHITECTURAL DECISION
 * ═══════════════════════════════════════════════════════════════
 *
 * This class is NOT shared with user-service. It is todo-service's
 * own internal representation of what a user looks like.
 *
 * This is the ANTI-CORRUPTION LAYER principle:
 *   - user-service controls its own UserResponse
 *   - todo-service maps only the fields IT cares about
 *   - If user-service adds a new field, todo-service is unaffected
 *   - If user-service renames a field, only this class needs to change
 *
 * Alternative (BAD): Share a common-model library with both services.
 *   Risk: a change to the shared library forces BOTH services to
 *   update and redeploy simultaneously — recreates deployment coupling.
 *
 * WHAT TODO-SERVICE CARES ABOUT:
 *   id   → to store as foreign key reference
 *   name → to embed in TodoResponse for display
 */
public class UserResponse {
    private Long id;
    private String name;
    private String email;
    private LocalDateTime createdAt;

    public UserResponse() {}

    public Long getId()                 { return id; }
    public String getName()             { return name; }
    public String getEmail()            { return email; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id)                          { this.id = id; }
    public void setName(String name)                    { this.name = name; }
    public void setEmail(String email)                  { this.email = email; }
    public void setCreatedAt(LocalDateTime createdAt)   { this.createdAt = createdAt; }
}
