package org.jarvis.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.jsonwebtoken.Claims;
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
import org.jarvis.security.util.TokenMaskingUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication service handling user registration, login, and token management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityMetrics securityMetrics;

    private static final String REVOKE_REASON_ROTATED = "REFRESH_ROTATED";
    private static final String REVOKE_REASON_LOGOUT = "USER_LOGOUT";
    private static final String REVOKE_REASON_REUSE = "REFRESH_REUSE_DETECTED";
    private static final String REVOKE_REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    private static final String REVOKE_REASON_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    private static final String REVOKE_REASON_SESSION_TIMEOUT = "SESSION_TIMEOUT";

    /**
     * Check if username already exists.
     */
    public boolean usernameExists(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * Register a new user.
     * 
     * @param request Registration request with username, password, role
     * @return Auth response with JWT tokens
     * @throws AuthenticationException if registration fails
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Double-check username doesn't exist (race condition protection)
        if (userRepository.existsByUsername(request.username())) {
            throw new AuthenticationException("USER_EXISTS", "Username already exists");
        }

        // Create new user with hashed password
        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .role("USER")
                .enabled(true)
                .build();

        user = userRepository.save(user);
        log.info("User registered: {}", user.getUsername());
        securityMetrics.auditEvent("USER_REGISTERED");

        return generateAuthResponse(user);
    }

    /**
     * Create bootstrap admin user if it does not exist yet.
     */
    @Transactional
    public void ensureBootstrapAdmin(String username, String rawPassword, String role) {
        if (userRepository.existsByUsername(username)) {
            log.info("Bootstrap admin already exists: {}", username);
            return;
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(rawPassword))
                .role(role == null || role.isBlank() ? "OWNER" : role.toUpperCase(Locale.ROOT))
                .enabled(true)
                .build();

        userRepository.save(user);
        log.warn("Bootstrap admin user created: {}", username);
    }

    /**
     * Login user and return JWT tokens.
     * 
     * @param request Login request with username and password
     * @return Auth response with JWT tokens
     * @throws AuthenticationException if credentials are invalid
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Find user by username
        Optional<User> maybeUser = userRepository.findByUsername(request.username());
        if (maybeUser.isEmpty()) {
            securityMetrics.loginFailure("INVALID_CREDENTIALS");
            securityMetrics.auditEvent("LOGIN_FAILURE");
            throw new AuthenticationException("INVALID_CREDENTIALS", "Invalid username or password");
        }
        User user = maybeUser.get();

        // Verify password
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Failed login attempt for user: {}", request.username());
            securityMetrics.loginFailure("INVALID_CREDENTIALS");
            securityMetrics.auditEvent("LOGIN_FAILURE");
            throw new AuthenticationException("INVALID_CREDENTIALS", "Invalid username or password");
        }

        // Check if user is enabled
        if (!user.isEnabled()) {
            log.warn("Login attempt for disabled user: {}", request.username());
            securityMetrics.loginFailure("ACCOUNT_DISABLED");
            securityMetrics.auditEvent("LOGIN_FAILURE");
            throw new AuthenticationException("ACCOUNT_DISABLED", "User account is disabled");
        }

        log.info("User logged in: {}", user.getUsername());
        securityMetrics.auditEvent("LOGIN_SUCCESS");
        return generateAuthResponse(user);
    }

    /**
     * Refresh access token using refresh token.
     * 
     * @param refreshToken The refresh token
     * @return Auth response with new JWT tokens
     * @throws AuthenticationException if refresh token is invalid
     */
    @Transactional(noRollbackFor = AuthenticationException.class)
    public AuthResponse refresh(String refreshToken) {
        Claims claims = jwtService.validateRefreshToken(refreshToken);
        Long userId = Long.parseLong(jwtService.extractUserId(claims));
        UUID tokenId = jwtService.extractTokenId(claims);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("USER_NOT_FOUND", "User not found"));

        // Check if user is still enabled
        if (!user.isEnabled()) {
            revokeAllRefreshTokens(user.getId(), REVOKE_REASON_ACCOUNT_DISABLED);
            securityMetrics.auditEvent("ACCOUNT_DISABLED_TOKENS_REVOKED");
            throw new AuthenticationException("ACCOUNT_DISABLED", "User account is disabled");
        }

        if (tokenId == null) {
            throw new AuthenticationException("INVALID_TOKEN",
                    "Refresh token is missing server-side session state; please log in again");
        }

        RefreshToken storedToken = refreshTokenRepository.findById(tokenId)
                .orElseThrow(() -> new AuthenticationException("INVALID_TOKEN", "Unknown refresh token"));

        Instant now = Instant.now();
        if (storedToken.getRevokedAt() != null) {
            if (storedToken.getReplacedByTokenId() != null) {
                revokeAllRefreshTokens(user.getId(), REVOKE_REASON_REUSE);
                securityMetrics.auditEvent("REFRESH_REUSE_DETECTED");
                log.warn("Refresh token reuse detected for user {}", user.getUsername());
                throw new AuthenticationException("TOKEN_REUSED",
                        "Refresh token reuse detected; all active sessions were revoked");
            }
            throw new AuthenticationException("TOKEN_REVOKED", "Refresh token has been revoked");
        }

        if (!storedToken.isActive(now)) {
            throw new AuthenticationException("TOKEN_EXPIRED", "Refresh token has expired");
        }

        Instant sessionStartedAt = storedToken.getSessionStartedAt() != null
                ? storedToken.getSessionStartedAt()
                : storedToken.getIssuedAt();
        long absoluteSessionTtlMs = jwtService.getAbsoluteSessionTtlMs();
        if (sessionStartedAt != null && absoluteSessionTtlMs > 0
                && now.isAfter(sessionStartedAt.plusMillis(absoluteSessionTtlMs))) {
            revokeAllRefreshTokens(user.getId(), REVOKE_REASON_SESSION_TIMEOUT);
            securityMetrics.auditEvent("SESSION_TIMEOUT");
            log.info("Session timeout for user {} (session started {})", user.getUsername(), sessionStartedAt);
            throw new AuthenticationException("SESSION_EXPIRED",
                    "Session has exceeded the maximum allowed duration; please log in again");
        }

        JwtService.IssuedRefreshToken replacementToken = jwtService.generateRefreshToken(user.getId().toString());

        // Atomic conditional UPDATE instead of a read-then-save check-then-act: the
        // WHERE clause re-checks revokedAt IS NULL at the moment of the write, so a
        // concurrent rotation of the same token can affect at most one row overall.
        int rotated = refreshTokenRepository.rotateIfActive(
                tokenId, now, replacementToken.tokenId(), REVOKE_REASON_ROTATED);
        if (rotated == 0) {
            // Lost the race: another request rotated/revoked this token between our
            // read above and this update. Treat exactly like detected token reuse.
            revokeAllRefreshTokens(user.getId(), REVOKE_REASON_REUSE);
            securityMetrics.auditEvent("REFRESH_REUSE_DETECTED");
            log.warn("Concurrent refresh-token rotation detected for user {}", user.getUsername());
            throw new AuthenticationException("TOKEN_REUSED",
                    "Refresh token reuse detected; all active sessions were revoked");
        }

        securityMetrics.tokenRevoked("single", REVOKE_REASON_ROTATED);
        persistRefreshToken(user.getId(), replacementToken,
                sessionStartedAt != null ? sessionStartedAt : replacementToken.issuedAt());

        log.debug("Token refreshed for user: {}", user.getUsername());
        return buildAuthResponse(user, replacementToken.token());
    }

    /**
     * Get user info from token.
     * 
     * @param token The JWT access token
     * @return User entity
     * @throws AuthenticationException if token is invalid or user not found
     */
    @Transactional(readOnly = true)
    public User getUserFromToken(String token) {
        try {
            Claims claims = jwtService.validateAccessToken(token);
            Long userId = Long.parseLong(jwtService.extractUserId(claims));
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new AuthenticationException("USER_NOT_FOUND", "User not found"));
            if (!user.isEnabled()) {
                throw new AuthenticationException("ACCOUNT_DISABLED", "User account is disabled");
            }
            if (isBeforeSessionFloor(user, claims)) {
                throw new AuthenticationException("TOKEN_REVOKED", "Token has been revoked");
            }
            return user;
        } catch (AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            // Message is passed through TokenMaskingUtil as defense-in-depth against a
            // raw token ever ending up embedded in an exception message.
            log.warn("Failed to extract user from token: {}", TokenMaskingUtil.maskTokensInText(e.getMessage()));
            throw new AuthenticationException("INVALID_TOKEN", "Invalid token");
        }
    }

    /**
     * Resolve the caller from an access token and enforce that their role
     * matches {@code requiredRole} (case-insensitive). Used by OWNER-only
     * admin endpoints ({@code AdminController}).
     *
     * @throws AuthenticationException if the token itself is invalid/expired/revoked
     * @throws AuthorizationException  if the token is valid but the caller's role does not match
     */
    public User requireRole(String token, String requiredRole) {
        User user = getUserFromToken(token);
        if (!requiredRole.equalsIgnoreCase(user.getRole())) {
            throw new AuthorizationException("FORBIDDEN_ROLE", "This action requires the " + requiredRole + " role");
        }
        return user;
    }

    /**
     * True if the access token predates the user's revoke-all-sessions floor
     * (set by {@code TokenRevocationService.revokeAllForUser}). Access tokens
     * have no individual server-side record, so this cheap per-user cutoff is
     * what makes "revoke all sessions" effective against already-issued
     * access tokens.
     */
    private boolean isBeforeSessionFloor(User user, Claims claims) {
        Instant floor = user.getTokensValidFrom();
        if (floor == null) {
            return false;
        }
        // JWT `iat` (NumericDate) is serialized at whole-second resolution, while the
        // revoke-all floor is captured at millisecond precision. Comparing them directly
        // would falsely reject an access token minted in the *same second* as the floor
        // (its iat=floor-truncated-to-second is < the millisecond floor) — e.g. a user
        // who revokes all sessions and immediately logs back in. Align the floor to the
        // same second granularity so tokens issued at or after the revoke second are
        // honoured; tokens from strictly earlier seconds are still rejected.
        Instant floorSecond = floor.truncatedTo(ChronoUnit.SECONDS);
        Instant issuedAt = jwtService.extractIssuedAt(claims);
        return issuedAt == null || issuedAt.isBefore(floorSecond);
    }

    @Transactional
    public void logout(String refreshToken) {
        Claims claims = jwtService.validateRefreshToken(refreshToken);
        UUID tokenId = jwtService.extractTokenId(claims);
        if (tokenId == null) {
            throw new AuthenticationException("INVALID_TOKEN",
                    "Refresh token is missing server-side session state; please log in again");
        }

        refreshTokenRepository.findById(tokenId).ifPresent(storedToken -> {
            if (storedToken.getRevokedAt() == null) {
                storedToken.setRevokedAt(Instant.now());
                storedToken.setRevokeReason(REVOKE_REASON_LOGOUT);
                refreshTokenRepository.save(storedToken);
                securityMetrics.tokenRevoked("single", REVOKE_REASON_LOGOUT);
                securityMetrics.auditEvent("LOGOUT");
            }
        });
    }

    @Transactional
    public AuthResponse changePassword(String accessToken, ChangePasswordRequest request) {
        User user = getUserFromToken(accessToken);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new AuthenticationException("INVALID_CREDENTIALS", "Current password is incorrect");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new IllegalArgumentException("New password must be different from the current password");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        revokeAllRefreshTokens(user.getId(), REVOKE_REASON_PASSWORD_CHANGED);
        securityMetrics.auditEvent("PASSWORD_CHANGED");

        log.info("Password changed for user {}", user.getUsername());
        return generateAuthResponse(user);
    }

    /**
     * Generate auth response with access and refresh tokens.
     */
    private AuthResponse generateAuthResponse(User user) {
        JwtService.IssuedRefreshToken refreshToken = jwtService.generateRefreshToken(user.getId().toString());
        persistRefreshToken(user.getId(), refreshToken, refreshToken.issuedAt());
        return buildAuthResponse(user, refreshToken.token());
    }

    private AuthResponse buildAuthResponse(User user, String refreshToken) {
        String accessToken = jwtService.generateAccessToken(
                user.getId().toString(),
                user.getUsername(),
                user.getRole());

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessExpirationMs() / 1000, // Convert to seconds
                user.getUsername(),
                user.getRole());
    }

    private void persistRefreshToken(Long userId, JwtService.IssuedRefreshToken refreshToken,
            Instant sessionStartedAt) {
        refreshTokenRepository.save(RefreshToken.builder()
                .tokenId(refreshToken.tokenId())
                .userId(userId)
                .issuedAt(refreshToken.issuedAt())
                .expiresAt(refreshToken.expiresAt())
                .sessionStartedAt(sessionStartedAt)
                .build());
    }

    /**
     * Revoke every outstanding refresh token for a user AND advance their
     * access-token validity floor to now, mirroring {@code
     * TokenRevocationService.revokeAllForUser}. Bumping {@code
     * tokensValidFrom} is what makes this effective against already-issued
     * access tokens (see {@link #isBeforeSessionFloor}) - without it, an
     * access token minted before a password change or account disablement
     * would remain valid until it naturally expires, even though its
     * refresh token (and the password/enabled state behind it) has already
     * been invalidated.
     */
    private void revokeAllRefreshTokens(Long userId, String reason) {
        Instant now = Instant.now();
        int revokedCount = refreshTokenRepository.revokeAllActiveTokensForUser(userId, now, reason);
        userRepository.findById(userId).ifPresent(user -> {
            user.setTokensValidFrom(now);
            userRepository.save(user);
        });
        securityMetrics.tokenRevoked("all", reason, revokedCount);
    }
}
