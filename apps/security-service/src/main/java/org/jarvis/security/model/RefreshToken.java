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

@Entity
@Table(name = "refresh_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    /**
     * When the overall session (rotation chain) started, independent of this
     * particular token's own {@link #issuedAt}. Carried over unchanged across
     * rotations so an absolute session TTL can be enforced even though each
     * individual refresh token gets a fresh {@code issuedAt}/{@code expiresAt}
     * on rotation.
     */
    @Column(name = "session_started_at", nullable = false)
    private Instant sessionStartedAt;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
