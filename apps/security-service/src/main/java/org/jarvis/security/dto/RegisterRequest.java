package org.jarvis.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Username is required") String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @Pattern(regexp = "USER", message = "Self-service registration only supports USER role")
        String role) {
    public RegisterRequest {
        if (role == null || role.isEmpty()) {
            role = "USER"; // Default role
        }
    }
}
