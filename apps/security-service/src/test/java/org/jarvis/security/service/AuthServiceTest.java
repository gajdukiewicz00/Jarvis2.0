package org.jarvis.security.service;

import io.jsonwebtoken.Claims;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.dto.AuthResponse;
import org.jarvis.security.dto.ChangePasswordRequest;
import org.jarvis.security.dto.LoginRequest;
import org.jarvis.security.dto.RegisterRequest;
import org.jarvis.security.model.RefreshToken;
import org.jarvis.security.model.User;
import org.jarvis.security.repository.RefreshTokenRepository;
import org.jarvis.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService} error paths and branches that are not
 * already exercised end-to-end by {@code SecurityServiceAuthIntegrationTest}
 * (user-not-found, expired/reused/revoked refresh tokens, disabled accounts,
 * bootstrap-admin bookkeeping, and password-change validation).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtService);
    }

    private User enabledUser(long id, String role) {
        return User.builder()
                .id(id)
                .username("alice")
                .password("hashed-password")
                .role(role)
                .enabled(true)
                .build();
    }

    private void stubSuccessfulTokenIssuance(User user, String accessToken, UUID refreshTokenId,
            String refreshTokenValue) {
        when(jwtService.generateAccessToken(user.getId().toString(), user.getUsername(), user.getRole()))
                .thenReturn(accessToken);
        when(jwtService.generateRefreshToken(user.getId().toString())).thenReturn(
                new JwtService.IssuedRefreshToken(refreshTokenId, refreshTokenValue, Instant.now(),
                        Instant.now().plusSeconds(600)));
        when(jwtService.getAccessExpirationMs()).thenReturn(3_600_000L);
    }

    // ------------------------------------------------------------------
    // register
    // ------------------------------------------------------------------

    @Test
    void registerThrowsUserExistsWhenRaceConditionDetected() {
        RegisterRequest request = new RegisterRequest("alice", "password123", "USER");
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.register(request));

        assertThat(ex.getErrorCode()).isEqualTo("USER_EXISTS");
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerCreatesUserAndReturnsTokens() {
        RegisterRequest request = new RegisterRequest("alice", "password123", "USER");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        User saved = enabledUser(1L, "USER");
        when(userRepository.save(any(User.class))).thenReturn(saved);
        stubSuccessfulTokenIssuance(saved, "access-tok", UUID.randomUUID(), "refresh-tok");

        AuthResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("access-tok");
        assertThat(response.refreshToken()).isEqualTo("refresh-tok");
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.role()).isEqualTo("USER");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    // ------------------------------------------------------------------
    // ensureBootstrapAdmin
    // ------------------------------------------------------------------

    @Test
    void ensureBootstrapAdminDoesNothingWhenUserAlreadyExists() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        authService.ensureBootstrapAdmin("admin", "strong-password-123", "ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    void ensureBootstrapAdminDefaultsRoleWhenBlank() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("strong-password-123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.ensureBootstrapAdmin("admin", "strong-password-123", "  ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void ensureBootstrapAdminDefaultsRoleWhenNull() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("strong-password-123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.ensureBootstrapAdmin("admin", "strong-password-123", null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void ensureBootstrapAdminUppercasesProvidedRole() {
        when(userRepository.existsByUsername("root")).thenReturn(false);
        when(passwordEncoder.encode("strong-password-123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.ensureBootstrapAdmin("root", "strong-password-123", "superadmin");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("SUPERADMIN");
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    @Test
    void loginThrowsInvalidCredentialsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.login(new LoginRequest("ghost", "whatever")));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loginThrowsInvalidCredentialsWhenPasswordDoesNotMatch() {
        User user = enabledUser(1L, "USER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPassword())).thenReturn(false);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.login(new LoginRequest("alice", "wrong")));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loginThrowsAccountDisabledWhenUserDisabled() {
        User user = enabledUser(1L, "USER");
        user.setEnabled(false);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.login(new LoginRequest("alice", "password123")));

        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void loginReturnsTokensOnSuccess() {
        User user = enabledUser(1L, "USER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);
        stubSuccessfulTokenIssuance(user, "access-tok", UUID.randomUUID(), "refresh-tok");

        AuthResponse response = authService.login(new LoginRequest("alice", "password123"));

        assertThat(response.accessToken()).isEqualTo("access-tok");
        assertThat(response.username()).isEqualTo("alice");
    }

    // ------------------------------------------------------------------
    // refresh
    // ------------------------------------------------------------------

    private Claims refreshClaimsFor(long userId, UUID tokenId) {
        Claims claims = mock(Claims.class);
        when(jwtService.validateRefreshToken("the-refresh-token")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(String.valueOf(userId));
        when(jwtService.extractTokenId(claims)).thenReturn(tokenId);
        return claims;
    }

    @Test
    void refreshThrowsUserNotFoundWhenUserMissing() {
        refreshClaimsFor(1L, UUID.randomUUID());
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void refreshRevokesAllTokensAndThrowsWhenAccountDisabled() {
        refreshClaimsFor(1L, UUID.randomUUID());
        User user = enabledUser(1L, "USER");
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_DISABLED");
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class),
                eq("ACCOUNT_DISABLED"));
    }

    @Test
    void refreshThrowsInvalidTokenWhenTokenIdMissing() {
        refreshClaimsFor(1L, null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(enabledUser(1L, "USER")));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void refreshThrowsInvalidTokenWhenStoredTokenUnknown() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        when(userRepository.findById(1L)).thenReturn(Optional.of(enabledUser(1L, "USER")));
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.empty());

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void refreshDetectsReuseAndRevokesAllSessionsWhenTokenWasAlreadyRotated() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        when(userRepository.findById(1L)).thenReturn(Optional.of(enabledUser(1L, "USER")));
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .revokedAt(Instant.now().minusSeconds(60))
                .replacedByTokenId(UUID.randomUUID())
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REUSED");
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class),
                eq("REFRESH_REUSE_DETECTED"));
    }

    @Test
    void refreshThrowsTokenRevokedWhenRevokedWithoutReplacement() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        when(userRepository.findById(1L)).thenReturn(Optional.of(enabledUser(1L, "USER")));
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .revokedAt(Instant.now().minusSeconds(60))
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void refreshThrowsTokenExpiredWhenPastExpiry() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        when(userRepository.findById(1L)).thenReturn(Optional.of(enabledUser(1L, "USER")));
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .expiresAt(Instant.now().minusSeconds(5))
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void refreshRotatesTokenOnSuccess() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));
        UUID newTokenId = UUID.randomUUID();
        stubSuccessfulTokenIssuance(user, "new-access-tok", newTokenId, "new-refresh-tok");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.refresh("the-refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-tok");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-tok");
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getReplacedByTokenId()).isEqualTo(newTokenId);
        assertThat(stored.getRevokeReason()).isEqualTo("REFRESH_ROTATED");
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    // ------------------------------------------------------------------
    // getUserFromToken
    // ------------------------------------------------------------------

    @Test
    void getUserFromTokenReturnsUserOnSuccess() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User result = authService.getUserFromToken("access-tok");

        assertThat(result).isEqualTo(user);
    }

    @Test
    void getUserFromTokenThrowsUserNotFoundWhenMissing() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("42");
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.getUserFromToken("access-tok"));

        assertThat(ex.getErrorCode()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void getUserFromTokenThrowsAccountDisabledWhenUserDisabled() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.getUserFromToken("access-tok"));

        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void getUserFromTokenWrapsUnexpectedRuntimeExceptionAsInvalidToken() {
        when(jwtService.validateAccessToken("garbage")).thenThrow(new IllegalStateException("malformed"));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.getUserFromToken("garbage"));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_TOKEN");
    }

    // ------------------------------------------------------------------
    // logout
    // ------------------------------------------------------------------

    @Test
    void logoutThrowsInvalidTokenWhenTokenIdMissing() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateRefreshToken("legacy")).thenReturn(claims);
        when(jwtService.extractTokenId(claims)).thenReturn(null);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.logout("legacy"));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void logoutIsNoopWhenTokenUnknown() {
        UUID tokenId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(jwtService.validateRefreshToken("tok")).thenReturn(claims);
        when(jwtService.extractTokenId(claims)).thenReturn(tokenId);
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.empty());

        authService.logout("tok");

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void logoutIsNoopWhenTokenAlreadyRevoked() {
        UUID tokenId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(jwtService.validateRefreshToken("tok")).thenReturn(claims);
        when(jwtService.extractTokenId(claims)).thenReturn(tokenId);
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .revokedAt(Instant.now().minusSeconds(30))
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));

        authService.logout("tok");

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void logoutRevokesActiveToken() {
        UUID tokenId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(jwtService.validateRefreshToken("tok")).thenReturn(claims);
        when(jwtService.extractTokenId(claims)).thenReturn(tokenId);
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));

        authService.logout("tok");

        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getRevokeReason()).isEqualTo("USER_LOGOUT");
        verify(refreshTokenRepository).save(stored);
    }

    // ------------------------------------------------------------------
    // changePassword
    // ------------------------------------------------------------------

    @Test
    void changePasswordThrowsInvalidCredentialsWhenCurrentPasswordWrong() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-current", user.getPassword())).thenReturn(false);

        ChangePasswordRequest request = new ChangePasswordRequest("wrong-current", "new-password-123");

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.changePassword("access-tok", request));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void changePasswordThrowsIllegalArgumentWhenNewPasswordMatchesCurrent() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);

        ChangePasswordRequest request = new ChangePasswordRequest("password123", "password123");

        assertThrows(IllegalArgumentException.class,
                () -> authService.changePassword("access-tok", request));

        verify(refreshTokenRepository, never()).revokeAllActiveTokensForUser(any(), any(), any());
    }

    @Test
    void changePasswordSucceedsAndRevokesExistingSessions() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(passwordEncoder.matches("new-password-123", "hashed-password")).thenReturn(false);
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hashed-password");
        stubSuccessfulTokenIssuance(user, "access-tok-2", UUID.randomUUID(), "refresh-tok-2");

        ChangePasswordRequest request = new ChangePasswordRequest("password123", "new-password-123");
        AuthResponse response = authService.changePassword("access-tok", request);

        assertThat(user.getPassword()).isEqualTo("new-hashed-password");
        assertThat(response.accessToken()).isEqualTo("access-tok-2");
        verify(userRepository).save(user);
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class),
                eq("PASSWORD_CHANGED"));
    }
}
