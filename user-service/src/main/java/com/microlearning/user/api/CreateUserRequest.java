package com.microlearning.user.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Inbound DTO — never expose the JPA entity directly. */
public class CreateUserRequest {
    @NotBlank(message = "Name is required")
    private String name;

    @Email @NotBlank(message = "Email is required")
    private String email;

    public CreateUserRequest() {}
    public String getName()          { return name; }
    public void setName(String n)    { this.name = n; }
    public String getEmail()         { return email; }
    public void setEmail(String e)   { this.email = e; }
}
