package org.jarvis.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * OWNER-only request to revoke a single access or refresh token. The token is
 * parsed server-side (not merely trusted) so its {@code jti}, type, and owner
 * can be resolved accurately.
 */
public record RevokeRequest(
        @NotBlank(message = "Token is required") String token,
        String reason) {
}
