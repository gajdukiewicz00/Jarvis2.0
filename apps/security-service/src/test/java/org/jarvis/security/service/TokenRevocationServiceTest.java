package org.jarvis.security.service;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OWNER-triggered revocation: single jti revocation
 * (recorded in the revoked-token store, plus the known refresh-token record
 * if applicable) and revoke-all-for-user (revokes stored refresh tokens and
 * bumps the user's access-token validity floor).
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

    private TokenRevocationService tokenRevocationService;

    @BeforeEach
    void setUp() {
        tokenRevocationService = new TokenRevocationService(
                jwtService, revokedTokenRepository, refreshTokenRepository, userRepository);
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
    }
}
