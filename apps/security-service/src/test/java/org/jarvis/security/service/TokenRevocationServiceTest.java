package org.jarvis.security.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.security.config.GlobalExceptionHandler.AuthorizationException;
import org.jarvis.security.metrics.SecurityMetrics;
import org.jarvis.security.model.RefreshToken;
import org.jarvis.security.model.RevokedToken;
import org.jarvis.security.model.User;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.RevokedTokenRepository;
import org.jarvis.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OWNER-triggered revocation: single jti revocation
 * (recorded in the revoked-token store, plus the known refresh-token record
 * if applicable), revoke-all-for-user (revokes stored refresh tokens and
 * bumps the user's access-token validity floor), and the self-service
 * {@link TokenRevocationService#revokeOwnSession}.
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private RevokedTokenRepository revokedTokenRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;

    private SimpleMeterRegistry meterRegistry;
    private TokenRevocationService tokenRevocationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tokenRevocationService = new TokenRevocationService(
                jwtService, revokedTokenRepository, refreshTokenRepository, userRepository,
                new SecurityMetrics(meterRegistry), auditService);
    }

    // ------------------------------------------------------------------
    // revokeToken
    // ------------------------------------------------------------------

    @Test
    void revokeTokenStoresJtiForAccessToken() {
        UUID jti = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(jwtService.prepareForRevocation("access-tok"))
                .thenReturn(new JwtService.RevocationTarget(jti, "access", 7L, expiresAt));
        when(revokedTokenRepository.existsById(jti)).thenReturn(false);

        TokenRevocationService.RevokedTokenInfo info =
                tokenRevocationService.revokeToken("access-tok", "SUSPICIOUS_ACTIVITY", 1L);

        assertThat(info.jti()).isEqualTo(jti);
        assertThat(info.tokenType()).isEqualTo("access");

        ArgumentCaptor<RevokedToken> captor = ArgumentCaptor.forClass(RevokedToken.class);
        verify(revokedTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getJti()).isEqualTo(jti);
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getRevokeReason()).isEqualTo("SUSPICIOUS_ACTIVITY");
        assertThat(captor.getValue().getRevokedBy()).isEqualTo(1L);
        verify(refreshTokenRepository, never()).findById(any());
        assertThat(meterRegistry
                .counter("security.token.revocations", "scope", "single", "reason", "SUSPICIOUS_ACTIVITY")
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("security.audit.events", "type", "TOKEN_REVOKED").count()).isEqualTo(1.0);
        verify(auditService).recordEvent("TOKEN_REVOKED", 7L, "SUSPICIOUS_ACTIVITY");
    }

    @Test
    void revokeTokenDoesNotDuplicateExistingRevocationRecord() {
        UUID jti = UUID.randomUUID();
        when(jwtService.prepareForRevocation("access-tok"))
                .thenReturn(new JwtService.RevocationTarget(jti, "access", 7L, Instant.now().plusSeconds(60)));
        when(revokedTokenRepository.existsById(jti)).thenReturn(true);

        tokenRevocationService.revokeToken("access-tok", null, 1L);

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void revokeTokenDefaultsReasonWhenBlank() {
        UUID jti = UUID.randomUUID();
        when(jwtService.prepareForRevocation("access-tok"))
                .thenReturn(new JwtService.RevocationTarget(jti, "access", 7L, Instant.now().plusSeconds(60)));

        tokenRevocationService.revokeToken("access-tok", "   ", 1L);

        ArgumentCaptor<RevokedToken> captor = ArgumentCaptor.forClass(RevokedToken.class);
        verify(revokedTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getRevokeReason()).isEqualTo("ADMIN_REVOKED");
    }

    @Test
    void revokeTokenAlsoMarksKnownRefreshTokenRecordRevoked() {
        UUID jti = UUID.randomUUID();
        when(jwtService.prepareForRevocation("refresh-tok"))
                .thenReturn(new JwtService.RevocationTarget(jti, "refresh", 7L, Instant.now().plusSeconds(60)));
        RefreshToken stored = RefreshToken.builder().tokenId(jti).userId(7L).build();
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.of(stored));

        tokenRevocationService.revokeToken("refresh-tok", "ADMIN_REVOKED", 1L);

        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getRevokeReason()).isEqualTo("ADMIN_REVOKED");
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void revokeTokenLeavesAlreadyRevokedRefreshTokenRecordUntouched() {
        UUID jti = UUID.randomUUID();
        when(jwtService.prepareForRevocation("refresh-tok"))
                .thenReturn(new JwtService.RevocationTarget(jti, "refresh", 7L, Instant.now().plusSeconds(60)));
        Instant originalRevokedAt = Instant.now().minusSeconds(120);
        RefreshToken stored = RefreshToken.builder()
                .tokenId(jti)
                .userId(7L)
                .revokedAt(originalRevokedAt)
                .revokeReason("USER_LOGOUT")
                .build();
        when(refreshTokenRepository.findById(jti)).thenReturn(Optional.of(stored));

        tokenRevocationService.revokeToken("refresh-tok", "ADMIN_REVOKED", 1L);

        assertThat(stored.getRevokedAt()).isEqualTo(originalRevokedAt);
        assertThat(stored.getRevokeReason()).isEqualTo("USER_LOGOUT");
        verify(refreshTokenRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // revokeAllForUser
    // ------------------------------------------------------------------

    @Test
    void revokeAllForUserRevokesRefreshTokensAndBumpsSessionFloor() {
        when(refreshTokenRepository.revokeAllActiveTokensForUser(eq(7L), any(Instant.class), eq("ADMIN_REVOKED_ALL")))
                .thenReturn(3);
        User user = User.builder().id(7L).username("alice").role("USER").enabled(true).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        int revoked = tokenRevocationService.revokeAllForUser(7L, null);

        assertThat(revoked).isEqualTo(3);
        assertThat(user.getTokensValidFrom()).isNotNull();
        verify(userRepository).save(user);
        assertThat(meterRegistry
                .counter("security.token.revocations", "scope", "all", "reason", "ADMIN_REVOKED_ALL")
                .count()).isEqualTo(3.0);
        assertThat(meterRegistry.counter("security.audit.events", "type", "TOKEN_REVOKED_ALL").count())
                .isEqualTo(1.0);
        verify(auditService).recordEvent("TOKEN_REVOKED_ALL", 7L, "ADMIN_REVOKED_ALL");
    }

    @Test
    void revokeAllForUserNormalizesExplicitReason() {
        when(refreshTokenRepository.revokeAllActiveTokensForUser(eq(7L), any(Instant.class), eq("OFFBOARDING")))
                .thenReturn(1);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        tokenRevocationService.revokeAllForUser(7L, "offboarding");

        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(7L), any(Instant.class), eq("OFFBOARDING"));
    }

    @Test
    void revokeAllForUserToleratesUnknownUser() {
        when(refreshTokenRepository.revokeAllActiveTokensForUser(eq(99L), any(Instant.class), eq("ADMIN_REVOKED_ALL")))
                .thenReturn(0);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        int revoked = tokenRevocationService.revokeAllForUser(99L, null);

        assertThat(revoked).isEqualTo(0);
        verify(userRepository, never()).save(any());
        assertThat(meterRegistry
                .counter("security.token.revocations", "scope", "all", "reason", "ADMIN_REVOKED_ALL")
                .count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("security.audit.events", "type", "TOKEN_REVOKED_ALL").count())
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // revokeOwnSession
    // ------------------------------------------------------------------

    @Test
    void revokeOwnSessionRevokesBothPresentedTokensWhenOwnedByCaller() {
        UUID accessJti = UUID.randomUUID();
        UUID refreshJti = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(jwtService.prepareForRevocation("refresh-tok"))
                .thenReturn(new JwtService.RevocationTarget(refreshJti, "refresh", 7L, expiresAt));
        when(jwtService.prepareForRevocation("access-tok"))
                .thenReturn(new JwtService.RevocationTarget(accessJti, "access", 7L, expiresAt));

        TokenRevocationService.RevokedSessionInfo info =
                tokenRevocationService.revokeOwnSession("access-tok", "refresh-tok", 7L);

        assertThat(info.accessJti()).isEqualTo(accessJti);
        assertThat(info.refreshJti()).isEqualTo(refreshJti);
        verify(revokedTokenRepository, times(2)).save(any(RevokedToken.class));
        assertThat(meterRegistry.counter("security.audit.events", "type", "SESSION_REVOKED_SELF").count())
                .isEqualTo(1.0);
        verify(auditService).recordEvent("SESSION_REVOKED_SELF", 7L, "USER_SESSION_REVOKED");
        verify(auditService, times(2)).recordEvent("TOKEN_REVOKED", 7L, "USER_SESSION_REVOKED");
    }

    @Test
    void revokeOwnSessionThrowsWhenRefreshTokenBelongsToDifferentUser() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(jwtService.prepareForRevocation("refresh-tok"))
                .thenReturn(new JwtService.RevocationTarget(UUID.randomUUID(), "refresh", 99L, expiresAt));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> tokenRevocationService.revokeOwnSession("access-tok", "refresh-tok", 7L));

        assertThat(ex.getErrorCode()).isEqualTo("SESSION_MISMATCH");
        verify(revokedTokenRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
