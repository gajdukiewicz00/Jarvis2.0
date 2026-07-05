package org.jarvis.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of an explicitly revoked JWT (access or refresh), keyed
 * by its {@code jti} claim. Consulted by
 * {@link org.jarvis.security.service.JwtService} on every token validation so
 * a revoked token is rejected even before its natural expiry.
 */
@Entity
@Table(name = "revoked_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevokedToken {

    @Id
    @Column(name = "jti", nullable = false, updatable = false)
    private UUID jti;

    @Column(name = "token_type", nullable = false, length = 20)
    private String tokenType; // "access" or "refresh"

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    @Column(name = "revoked_by")
    private Long revokedBy;
}
