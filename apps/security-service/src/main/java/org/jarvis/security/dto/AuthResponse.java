package org.jarvis.security.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String username,
        String role) {
}
