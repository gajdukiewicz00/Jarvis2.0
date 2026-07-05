package org.jarvis.security.repository;

import org.jarvis.security.model.RefreshToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Recent revocation history (rotation, reuse, logout, password change, admin revoke, ...). */
    List<RefreshToken> findByRevokedAtIsNotNullOrderByRevokedAtDesc(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken token
            set token.revokedAt = :revokedAt,
                token.revokeReason = :reason
            where token.userId = :userId
              and token.revokedAt is null
              and token.expiresAt > :revokedAt
            """)
    int revokeAllActiveTokensForUser(@Param("userId") Long userId,
                                     @Param("revokedAt") Instant revokedAt,
                                     @Param("reason") String reason);
}
