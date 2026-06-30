package com.microlearning.todo.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * Todo — Aggregate Root of the Task Management Bounded Context.
 *
 * MICROSERVICES NOTE:
 * Compare this to the monolith's Todo entity:
 *
 * MONOLITH:
 *   @ManyToOne User user;          ← JPA FK join to the users table
 *
 * MICROSERVICE:
 *   Long userId;                   ← just an ID, not a JPA relationship
 *   String userName;               ← denormalized snapshot from user-service
 *
 * WHY:
 * todos table and users table are in DIFFERENT DATABASES.
 * JPA cannot JOIN across databases. So we:
 *   1. Store userId as a plain Long (to reference the user)
 *   2. Store userName as a snapshot (to avoid calling user-service on every read)
 *
 * TRADE-OFF (discussed in Phase 2):
 *   If a user renames themselves, existing todos still show the old name.
 *   For this domain, that is acceptable (todos show who created them at the time).
 *   Phase 4 option: consume UserUpdated event and refresh the snapshot.
 */
@Entity
@Table(name = "todos")
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String title;

    private String description;

    @Column(nullable = false)
    private boolean completed = false;

    /** Plain Long reference — NO @ManyToOne, NO join to users table */
    @Column(nullable = false)
    private Long userId;

    /**
     * Denormalized snapshot of the user's name at todo-creation time.
     * Populated from user-service response on POST /api/todos.
     * Not updated automatically — see trade-off note above.
     */
    @Column
    private String userName;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public Todo() {}

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public Long getId()                  { return id; }
    public String getTitle()             { return title; }
    public String getDescription()       { return description; }
    public boolean isCompleted()         { return completed; }
    public Long getUserId()              { return userId; }
    public String getUserName()          { return userName; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getCompletedAt(){ return completedAt; }

    public void setTitle(String t)               { this.title = t; }
    public void setDescription(String d)         { this.description = d; }
    public void setCompleted(boolean c)          { this.completed = c; }
    public void setUserId(Long uid)              { this.userId = uid; }
    public void setUserName(String name)         { this.userName = name; }
    public void setCompletedAt(LocalDateTime t)  { this.completedAt = t; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String title, description, userName;
        private Long userId;
        private boolean completed = false;

        public Builder title(String t)       { this.title = t;       return this; }
        public Builder description(String d) { this.description = d; return this; }
        public Builder userId(Long uid)      { this.userId = uid;    return this; }
        public Builder userName(String name) { this.userName = name; return this; }
        public Builder completed(boolean c)  { this.completed = c;   return this; }

        public Todo build() {
            Todo t = new Todo();
            t.title = this.title; t.description = this.description;
            t.userId = this.userId; t.userName = this.userName;
            t.completed = this.completed;
            return t;
        }
    }
}
