package org.jarvis.security.service;

import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.config.GlobalExceptionHandler.AuthorizationException;
import org.jarvis.security.dto.AuthResponse;
import org.jarvis.security.dto.ChangePasswordRequest;
import org.jarvis.security.dto.LoginRequest;
import org.jarvis.security.dto.RegisterRequest;
import org.jarvis.security.metrics.SecurityMetrics;
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

    private SimpleMeterRegistry meterRegistry;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtService,
                new SecurityMetrics(meterRegistry));
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
        assertThat(meterRegistry.counter("security.audit.events", "type", "USER_REGISTERED").count())
                .isEqualTo(1.0);
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
        assertThat(captor.getValue().getRole()).isEqualTo("OWNER");
    }

    @Test
    void ensureBootstrapAdminDefaultsRoleWhenNull() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("strong-password-123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.ensureBootstrapAdmin("admin", "strong-password-123", null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("OWNER");
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
        assertThat(meterRegistry.counter("security.login.failures", "reason", "INVALID_CREDENTIALS").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("security.audit.events", "type", "LOGIN_FAILURE").count()).isEqualTo(1.0);
    }

    @Test
    void loginThrowsInvalidCredentialsWhenPasswordDoesNotMatch() {
        User user = enabledUser(1L, "USER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPassword())).thenReturn(false);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.login(new LoginRequest("alice", "wrong")));

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(meterRegistry.counter("security.login.failures", "reason", "INVALID_CREDENTIALS").count())
                .isEqualTo(1.0);
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
        assertThat(meterRegistry.counter("security.login.failures", "reason", "ACCOUNT_DISABLED").count())
                .isEqualTo(1.0);
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
        assertThat(meterRegistry.counter("security.audit.events", "type", "LOGIN_SUCCESS").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("security.login.failures", "reason", "INVALID_CREDENTIALS").count())
                .isEqualTo(0.0);
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
        assertThat(meterRegistry.counter("security.audit.events", "type", "ACCOUNT_DISABLED_TOKENS_REVOKED").count())
                .isEqualTo(1.0);
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
        assertThat(meterRegistry.counter("security.audit.events", "type", "REFRESH_REUSE_DETECTED").count())
                .isEqualTo(1.0);
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
        when(refreshTokenRepository.rotateIfActive(eq(tokenId), any(Instant.class), eq(newTokenId),
                eq("REFRESH_ROTATED"))).thenReturn(1);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.refresh("the-refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-tok");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-tok");
        verify(refreshTokenRepository).rotateIfActive(eq(tokenId), any(Instant.class), eq(newTokenId),
                eq("REFRESH_ROTATED"));
        // Only the new replacement token is persisted via save(); the original
        // token's revocation now goes through the atomic rotateIfActive() update.
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
        assertThat(meterRegistry.counter("security.token.revocations", "scope", "single", "reason", "REFRESH_ROTATED")
                .count()).isEqualTo(1.0);
    }

    @Test
    void refreshCarriesOverSessionStartedAtInsteadOfResettingItOnRotation() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Instant originalSessionStart = Instant.now().minusSeconds(3600);
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .sessionStartedAt(originalSessionStart)
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));
        UUID newTokenId = UUID.randomUUID();
        stubSuccessfulTokenIssuance(user, "new-access-tok", newTokenId, "new-refresh-tok");
        when(refreshTokenRepository.rotateIfActive(eq(tokenId), any(Instant.class), eq(newTokenId),
                eq("REFRESH_ROTATED"))).thenReturn(1);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.refresh("the-refresh-token");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(1)).save(captor.capture());
        RefreshToken persistedNewToken = captor.getValue();
        assertThat(persistedNewToken.getSessionStartedAt()).isEqualTo(originalSessionStart);
    }

    @Test
    void refreshThrowsSessionExpiredWhenAbsoluteSessionTtlExceeded() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Instant longAgo = Instant.now().minusSeconds(400_000);
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .sessionStartedAt(longAgo)
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));
        when(jwtService.getAbsoluteSessionTtlMs()).thenReturn(1_000L); // far shorter than "longAgo"

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("SESSION_EXPIRED");
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class),
                eq("SESSION_TIMEOUT"));
        verify(jwtService, never()).generateRefreshToken(any());
        assertThat(meterRegistry.counter("security.audit.events", "type", "SESSION_TIMEOUT").count())
                .isEqualTo(1.0);
    }

    @Test
    void refreshDoesNotEnforceSessionTimeoutWhenAbsoluteSessionTtlDisabled() {
        UUID tokenId = UUID.randomUUID();
        refreshClaimsFor(1L, tokenId);
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        RefreshToken stored = RefreshToken.builder()
                .tokenId(tokenId)
                .userId(1L)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .sessionStartedAt(Instant.now().minusSeconds(999_999_999))
                .build();
        when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));
        when(jwtService.getAbsoluteSessionTtlMs()).thenReturn(0L); // disabled
        UUID newTokenId = UUID.randomUUID();
        stubSuccessfulTokenIssuance(user, "new-access-tok", newTokenId, "new-refresh-tok");
        when(refreshTokenRepository.rotateIfActive(eq(tokenId), any(Instant.class), eq(newTokenId),
                eq("REFRESH_ROTATED"))).thenReturn(1);

        AuthResponse response = authService.refresh("the-refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-tok");
    }

    @Test
    void refreshDetectsConcurrentRotationRaceAndRevokesAllSessions() {
        // Simulates the losing side of two concurrent refresh() calls racing on the
        // same still-valid refresh token: both read the row with revokedAt == null
        // (line 172-189 checks pass for both), but the atomic conditional UPDATE
        // (rotateIfActive) can only let one of them actually flip revokedAt. The
        // loser must see affectedRows == 0 and treat it as reuse/replay - never
        // silently succeed and mint a second live child token from one rotation.
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
        // Only stub the refresh-token issuance step reached before rotateIfActive();
        // buildAuthResponse() is never reached on the losing path, so access-token
        // generation is intentionally left unstubbed.
        when(jwtService.generateRefreshToken(user.getId().toString())).thenReturn(
                new JwtService.IssuedRefreshToken(UUID.randomUUID(), "new-refresh-tok", Instant.now(),
                        Instant.now().plusSeconds(600)));
        // A concurrent winner already revoked the row between our read above and
        // this conditional UPDATE, so the WHERE clause matches zero rows.
        when(refreshTokenRepository.rotateIfActive(eq(tokenId), any(Instant.class), any(UUID.class),
                eq("REFRESH_ROTATED"))).thenReturn(0);

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.refresh("the-refresh-token"));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REUSED");
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class),
                eq("REFRESH_REUSE_DETECTED"));
        // The loser must not persist a replacement token for a rotation it never won.
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        assertThat(meterRegistry.counter("security.audit.events", "type", "REFRESH_REUSE_DETECTED").count())
                .isEqualTo(1.0);
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

    @Test
    void getUserFromTokenThrowsTokenRevokedWhenIssuedBeforeSessionFloor() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        Instant floor = Instant.now();
        user.setTokensValidFrom(floor);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtService.extractIssuedAt(claims)).thenReturn(floor.minusSeconds(60));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> authService.getUserFromToken("access-tok"));

        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void getUserFromTokenSucceedsWhenIssuedAfterSessionFloor() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        Instant floor = Instant.now();
        user.setTokensValidFrom(floor);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtService.extractIssuedAt(claims)).thenReturn(floor.plusSeconds(60));

        User result = authService.getUserFromToken("access-tok");

        assertThat(result).isEqualTo(user);
    }

    @Test
    void getUserFromTokenSucceedsWhenNoSessionFloorSet() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("access-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER"); // tokensValidFrom left null
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User result = authService.getUserFromToken("access-tok");

        assertThat(result).isEqualTo(user);
    }

    // ------------------------------------------------------------------
    // requireRole
    // ------------------------------------------------------------------

    @Test
    void requireRoleReturnsUserWhenRoleMatchesCaseInsensitively() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("owner-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User owner = enabledUser(1L, "OWNER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        User result = authService.requireRole("owner-tok", "owner");

        assertThat(result).isEqualTo(owner);
    }

    @Test
    void requireRoleThrowsAuthorizationExceptionWhenRoleDoesNotMatch() {
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("user-tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("1");
        User user = enabledUser(1L, "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> authService.requireRole("user-tok", "OWNER"));

        assertThat(ex.getErrorCode()).isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void requireRolePropagatesAuthenticationExceptionForInvalidToken() {
        when(jwtService.validateAccessToken("bad-tok"))
                .thenThrow(new AuthenticationException("INVALID_TOKEN", "Invalid token"));

        assertThrows(AuthenticationException.class, () -> authService.requireRole("bad-tok", "OWNER"));
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
        assertThat(meterRegistry.counter("security.audit.events", "type", "LOGOUT").count()).isEqualTo(0.0);
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
        assertThat(meterRegistry.counter("security.token.revocations", "scope", "single", "reason", "USER_LOGOUT")
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("security.audit.events", "type", "LOGOUT").count()).isEqualTo(1.0);
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
        // Saved twice: once for the new password, once more when the
        // session-floor bump (tokensValidFrom) is persisted so previously
        // issued access tokens are rejected (see revokeAllRefreshTokens).
        verify(userRepository, times(2)).save(user);
        assertThat(user.getTokensValidFrom()).isNotNull();
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class),
                eq("PASSWORD_CHANGED"));
        assertThat(meterRegistry.counter("security.audit.events", "type", "PASSWORD_CHANGED").count())
                .isEqualTo(1.0);
    }
}
