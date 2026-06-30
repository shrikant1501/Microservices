package com.microlearning.todo.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * CreateUserRequest — inbound DTO for creating a user.
 *
 * ARCHITECTURE NOTE:
 * In microservices, DTOs form the "contract" of your service's API.
 * Never expose your JPA Entity directly from a REST endpoint —
 * that couples your API contract to your database schema.
 */
public class CreateUserRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email")
    private String email;

    public CreateUserRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
