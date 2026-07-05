package org.jarvis.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.model.RevokedToken;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.jarvis.security.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * OWNER-triggered token revocation: revoking a single token by its jti, or
 * revoking every outstanding session for a user. Complements the automatic
 * revocation paths already in {@link AuthService} (logout, password change,
 * refresh rotation/reuse detection).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private static final String DEFAULT_SINGLE_REVOKE_REASON = "ADMIN_REVOKED";
    private static final String DEFAULT_REVOKE_ALL_REASON = "ADMIN_REVOKED_ALL";

    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    /**
     * Revoke a single token by parsing it (accepting either an access or a
     * refresh token) and recording its {@code jti} in the revoked-token
     * store. If the token is a refresh token with a known server-side
     * record, that record is also marked revoked so {@link AuthService#refresh}
     * rejects it immediately rather than relying solely on the jti lookup.
     */
    @Transactional
    public RevokedTokenInfo revokeToken(String token, String reason, Long revokedByUserId) {
        JwtService.RevocationTarget target = jwtService.prepareForRevocation(token);
        String normalizedReason = normalizeReason(reason, DEFAULT_SINGLE_REVOKE_REASON);
        Instant now = Instant.now();

        if (!revokedTokenRepository.existsById(target.jti())) {
            revokedTokenRepository.save(RevokedToken.builder()
                    .jti(target.jti())
                    .tokenType(target.tokenType())
                    .userId(target.userId())
                    .revokedAt(now)
                    .expiresAt(target.expiresAt())
                    .revokeReason(normalizedReason)
                    .revokedBy(revokedByUserId)
                    .build());
        }

        if ("refresh".equals(target.tokenType())) {
            refreshTokenRepository.findById(target.jti()).ifPresent(stored -> {
                if (stored.getRevokedAt() == null) {
                    stored.setRevokedAt(now);
                    stored.setRevokeReason(normalizedReason);
                    refreshTokenRepository.save(stored);
                }
            });
        }

        log.info("Token revoked: jti={} type={} userId={} revokedBy={}",
                target.jti(), target.tokenType(), target.userId(), revokedByUserId);
        return new RevokedTokenInfo(target.jti(), target.tokenType());
    }

    /**
     * Revoke every outstanding refresh token for a user and move their
     * access-token validity floor to now, so previously issued access tokens
     * are rejected on next use even though they are individually untracked.
     */
    @Transactional
    public int revokeAllForUser(Long userId, String reason) {
        Instant now = Instant.now();
        String normalizedReason = normalizeReason(reason, DEFAULT_REVOKE_ALL_REASON);

        int revokedRefreshTokens =
                refreshTokenRepository.revokeAllActiveTokensForUser(userId, now, normalizedReason);

        userRepository.findById(userId).ifPresent(user -> {
            user.setTokensValidFrom(now);
            userRepository.save(user);
        });

        log.info("Revoked all sessions for user {} ({} active refresh tokens)", userId, revokedRefreshTokens);
        return revokedRefreshTokens;
    }

    private String normalizeReason(String reason, String fallback) {
        return (reason == null || reason.isBlank()) ? fallback : reason.trim().toUpperCase(Locale.ROOT);
    }

    public record RevokedTokenInfo(UUID jti, String tokenType) {
    }
}
