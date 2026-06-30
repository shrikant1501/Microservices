package com.microlearning.todo.todo;

import java.time.LocalDateTime;

public class TodoResponse {
    private Long id;
    private String title;
    private String description;
    private boolean completed;
    private Long userId;
    private String userName;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public TodoResponse() {}

    private TodoResponse(Builder b) {
        this.id = b.id; this.title = b.title; this.description = b.description;
        this.completed = b.completed; this.userId = b.userId; this.userName = b.userName;
        this.createdAt = b.createdAt; this.completedAt = b.completedAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public boolean isCompleted() { return completed; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id; private String title; private String description;
        private boolean completed; private Long userId; private String userName;
        private LocalDateTime createdAt; private LocalDateTime completedAt;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder title(String t) { this.title = t; return this; }
        public Builder description(String d) { this.description = d; return this; }
        public Builder completed(boolean c) { this.completed = c; return this; }
        public Builder userId(Long uid) { this.userId = uid; return this; }
        public Builder userName(String name) { this.userName = name; return this; }
        public Builder createdAt(LocalDateTime t) { this.createdAt = t; return this; }
        public Builder completedAt(LocalDateTime t) { this.completedAt = t; return this; }

        public TodoResponse build() { return new TodoResponse(this); }
    }
}
