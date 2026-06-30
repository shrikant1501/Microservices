package com.microlearning.todo.api;

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
        id=b.id; title=b.title; description=b.description; completed=b.completed;
        userId=b.userId; userName=b.userName; createdAt=b.createdAt; completedAt=b.completedAt;
    }

    public Long getId()                  { return id; }
    public String getTitle()             { return title; }
    public String getDescription()       { return description; }
    public boolean isCompleted()         { return completed; }
    public Long getUserId()              { return userId; }
    public String getUserName()          { return userName; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getCompletedAt(){ return completedAt; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private Long id, userId; private String title, description, userName;
        private boolean completed; private LocalDateTime createdAt, completedAt;
        public Builder id(Long v)              { this.id = v;           return this; }
        public Builder title(String v)         { this.title = v;        return this; }
        public Builder description(String v)   { this.description = v;  return this; }
        public Builder completed(boolean v)    { this.completed = v;    return this; }
        public Builder userId(Long v)          { this.userId = v;       return this; }
        public Builder userName(String v)      { this.userName = v;     return this; }
        public Builder createdAt(LocalDateTime v){ this.createdAt = v;  return this; }
        public Builder completedAt(LocalDateTime v){ this.completedAt=v;return this; }
        public TodoResponse build() { return new TodoResponse(this); }
    }
}
