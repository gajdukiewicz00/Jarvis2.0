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

    /**
     * Atomically rotate a single refresh token: only revokes {@code tokenId} and
     * records its replacement if the token is still un-revoked at the moment the
     * UPDATE executes. This replaces a non-atomic read-then-save check-then-act:
     * two concurrent callers that both read the same not-yet-revoked row will
     * race on this UPDATE, and the database guarantees exactly one of them sees
     * {@code revokedAt is null} true and gets {@code affectedRows == 1}; the
     * loser gets {@code affectedRows == 0} and must treat that as a concurrent
     * rotation / reuse condition rather than silently succeeding.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken token
            set token.revokedAt = :revokedAt,
                token.replacedByTokenId = :replacedByTokenId,
                token.revokeReason = :reason
            where token.tokenId = :tokenId
              and token.revokedAt is null
            """)
    int rotateIfActive(@Param("tokenId") UUID tokenId,
                        @Param("revokedAt") Instant revokedAt,
                        @Param("replacedByTokenId") UUID replacedByTokenId,
                        @Param("reason") String reason);
}
