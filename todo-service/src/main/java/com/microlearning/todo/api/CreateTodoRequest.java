package com.microlearning.todo.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateTodoRequest {
    @NotBlank(message = "Title is required")
    private String title;
    private String description;
    @NotNull(message = "userId is required")
    private Long userId;

    public CreateTodoRequest() {}
    public String getTitle()           { return title; }
    public void setTitle(String t)     { this.title = t; }
    public String getDescription()     { return description; }
    public void setDescription(String d){ this.description = d; }
    public Long getUserId()            { return userId; }
    public void setUserId(Long id)     { this.userId = id; }
}
