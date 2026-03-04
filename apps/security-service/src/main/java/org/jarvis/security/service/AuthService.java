package org.jarvis.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.dto.AuthResponse;
import org.jarvis.security.dto.LoginRequest;
import org.jarvis.security.dto.RegisterRequest;
import org.jarvis.security.model.User;
import org.jarvis.security.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Authentication service handling user registration, login, and token management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

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
                .role(request.role() != null ? request.role() : "USER")
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        // Validate refresh token type
        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new AuthenticationException("INVALID_TOKEN", "Invalid refresh token");
        }

        // Check expiration
        if (jwtService.isTokenExpired(refreshToken)) {
            throw new AuthenticationException("TOKEN_EXPIRED", "Refresh token has expired");
        }

        // Extract user ID from refresh token
        String userId = jwtService.extractUserId(refreshToken);
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new AuthenticationException(
                    "USER_NOT_FOUND", "User not found"));

        // Check if user is still enabled
        if (!user.isEnabled()) {
            throw new AuthenticationException("ACCOUNT_DISABLED", "User account is disabled");
        }

        log.debug("Token refreshed for user: {}", user.getUsername());
        return generateAuthResponse(user);
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
            // Check expiration first
            if (jwtService.isTokenExpired(token)) {
                throw new AuthenticationException("TOKEN_EXPIRED", "Token has expired");
            }
            
            String userId = jwtService.extractUserId(token);
            return userRepository.findById(Long.parseLong(userId))
                    .orElseThrow(() -> new AuthenticationException(
                        "USER_NOT_FOUND", "User not found"));
        } catch (AuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Failed to extract user from token: {}", e.getMessage());
            throw new AuthenticationException("INVALID_TOKEN", "Invalid token");
        }
    }

    /**
     * Generate auth response with access and refresh tokens.
     */
    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(
                user.getId().toString(),
                user.getUsername(),
                user.getRole());

        String refreshToken = jwtService.generateRefreshToken(user.getId().toString());

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessExpirationMs() / 1000, // Convert to seconds
                user.getUsername(),
                user.getRole());
    }
}
