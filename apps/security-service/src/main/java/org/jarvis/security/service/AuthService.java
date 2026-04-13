package org.jarvis.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
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

    private static final String REVOKE_REASON_ROTATED = "REFRESH_ROTATED";
    private static final String REVOKE_REASON_LOGOUT = "USER_LOGOUT";
    private static final String REVOKE_REASON_REUSE = "REFRESH_REUSE_DETECTED";
    private static final String REVOKE_REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    private static final String REVOKE_REASON_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

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
                .role(role == null || role.isBlank() ? "ADMIN" : role.toUpperCase(Locale.ROOT))
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
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new AuthenticationException(
                    "INVALID_CREDENTIALS", "Invalid username or password"));

        // Verify password
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Failed login attempt for user: {}", request.username());
            throw new AuthenticationException("INVALID_CREDENTIALS", "Invalid username or password");
        }

        // Check if user is enabled
        if (!user.isEnabled()) {
            log.warn("Login attempt for disabled user: {}", request.username());
            throw new AuthenticationException("ACCOUNT_DISABLED", "User account is disabled");
        }

        log.info("User logged in: {}", user.getUsername());
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
                log.warn("Refresh token reuse detected for user {}", user.getUsername());
                throw new AuthenticationException("TOKEN_REUSED",
                        "Refresh token reuse detected; all active sessions were revoked");
            }
            throw new AuthenticationException("TOKEN_REVOKED", "Refresh token has been revoked");
        }

        if (!storedToken.isActive(now)) {
            throw new AuthenticationException("TOKEN_EXPIRED", "Refresh token has expired");
        }

        JwtService.IssuedRefreshToken replacementToken = jwtService.generateRefreshToken(user.getId().toString());
        storedToken.setRevokedAt(now);
        storedToken.setReplacedByTokenId(replacementToken.tokenId());
        storedToken.setRevokeReason(REVOKE_REASON_ROTATED);
        refreshTokenRepository.save(storedToken);
        persistRefreshToken(user.getId(), replacementToken);

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
            return user;
        } catch (AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Failed to extract user from token: {}", e.getMessage());
            throw new AuthenticationException("INVALID_TOKEN", "Invalid token");
        }
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

        log.info("Password changed for user {}", user.getUsername());
        return generateAuthResponse(user);
    }

    /**
     * Generate auth response with access and refresh tokens.
     */
    private AuthResponse generateAuthResponse(User user) {
        JwtService.IssuedRefreshToken refreshToken = jwtService.generateRefreshToken(user.getId().toString());
        persistRefreshToken(user.getId(), refreshToken);
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

    private void persistRefreshToken(Long userId, JwtService.IssuedRefreshToken refreshToken) {
        refreshTokenRepository.save(RefreshToken.builder()
                .tokenId(refreshToken.tokenId())
                .userId(userId)
                .issuedAt(refreshToken.issuedAt())
                .expiresAt(refreshToken.expiresAt())
                .build());
    }

    private void revokeAllRefreshTokens(Long userId, String reason) {
        refreshTokenRepository.revokeAllActiveTokensForUser(userId, Instant.now(), reason);
    }
}
