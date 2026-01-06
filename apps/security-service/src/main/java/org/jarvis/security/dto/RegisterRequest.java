package org.jarvis.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank(message = "Username is required") String username,

        @NotBlank(message = "Password is required") String password,

        @Pattern(regexp = "USER|ADMIN", message = "Role must be USER or ADMIN") String role) {
    public RegisterRequest {
        if (role == null || role.isEmpty()) {
            role = "USER"; // Default role
        }
    }
}
