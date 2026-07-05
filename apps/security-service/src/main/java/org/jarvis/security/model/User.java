package org.jarvis.security.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 100)
    private String username;

    @Column(nullable = false)
    private String password; // BCrypt hash

    @Column(nullable = false, length = 50)
    private String role; // see org.jarvis.security.model.Role: OWNER, ADMIN, USER, GUEST, SERVICE

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Access-token validity floor. When set, any access token whose {@code iat}
     * precedes this timestamp is rejected even though individual access-token
     * jtis are not tracked server-side. Bumped by
     * {@code TokenRevocationService.revokeAllForUser}. Null means no floor.
     */
    @Column(name = "tokens_valid_from")
    private Instant tokensValidFrom;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
