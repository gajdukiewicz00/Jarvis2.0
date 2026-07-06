package org.jarvis.security.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.config.GlobalExceptionHandler.UserAlreadyExistsException;
import org.jarvis.security.dto.AuthResponse;
import org.jarvis.security.dto.ChangePasswordRequest;
import org.jarvis.security.dto.LoginRequest;
import org.jarvis.security.dto.RefreshRequest;
import org.jarvis.security.dto.RegisterRequest;
import org.jarvis.security.model.User;
import org.jarvis.security.service.AuthService;
import org.jarvis.security.service.TokenRevocationService;
import org.jarvis.security.util.BearerTokenExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication controller for user registration, login, and token management.
 *
 * All endpoints are public and handle:
 * - POST /auth/register - Register new user
 * - POST /auth/login - Login and get JWT tokens
 * - POST /auth/refresh - Refresh access token
 * - POST /auth/logout - Revoke current refresh token
 * - POST /auth/revoke-current - Revoke the caller's own current session (access + refresh)
 * - POST /auth/password/change - Change password and revoke prior refresh tokens
 * - GET /auth/me - Get current user info
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenRevocationService tokenRevocationService;

    /**
     * Register a new user.
     * 
     * @param request Registration data (username, password, role)
     * @return JWT tokens on success, error on failure
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration attempt for user: {}", request.username());
        
        // Check if username already exists - throw custom exception
        if (authService.usernameExists(request.username())) {
            throw new UserAlreadyExistsException("Username '" + request.username() + "' already exists");
        }
        
        AuthResponse response = authService.register(request);
        log.info("User registered successfully: {}", request.username());
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Login with username and password.
     * 
     * @param request Login credentials
     * @return JWT tokens on success
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for user: {}", request.username());
        
        AuthResponse response = authService.login(request);
        log.info("User logged in successfully: {}", request.username());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Refresh access token using refresh token.
     * 
     * @param request Refresh token
     * @return New JWT tokens
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        log.debug("Token refresh attempt");
        
        AuthResponse response = authService.refresh(request.refreshToken());
        log.debug("Token refreshed successfully");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Logout by revoking the supplied refresh token.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Revoke the caller's own current session: unlike {@link #logout} (which
     * only marks the presented refresh token revoked), this blacklists the
     * jti of both the presented access token (Authorization header) and the
     * presented refresh token (request body), so the access token is
     * rejected immediately rather than only once naturally expired. Distinct
     * from the OWNER-only {@code AdminController#revokeAll} - a caller may
     * only revoke their own session this way.
     */
    @PostMapping("/revoke-current")
    public ResponseEntity<Map<String, Object>> revokeCurrentSession(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody RefreshRequest request) {

        String accessToken = BearerTokenExtractor.extract(authHeader);
        User caller = authService.getUserFromToken(accessToken);

        TokenRevocationService.RevokedSessionInfo info =
                tokenRevocationService.revokeOwnSession(accessToken, request.refreshToken(), caller.getId());

        log.info("User '{}' revoked their own current session", caller.getUsername());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revoked", true);
        body.put("accessJti", info.accessJti().toString());
        body.put("refreshJti", info.refreshJti().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * Get current user info from JWT token.
     * 
     * @param authHeader Authorization header with Bearer token
     * @return User info
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        // Validate Authorization header
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AuthenticationException("MISSING_TOKEN", "Missing or invalid Authorization header");
        }
        
        String token = authHeader.substring(7);
        if (token.isEmpty()) {
            throw new AuthenticationException("EMPTY_TOKEN", "Token is empty");
        }
        
        User user = authService.getUserFromToken(token);
        
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("role", user.getRole());
        userInfo.put("enabled", user.isEnabled());
        
        return ResponseEntity.ok(userInfo);
    }

    /**
     * Change password and issue a new token pair for the current session.
     */
    @PostMapping("/password/change")
    public ResponseEntity<AuthResponse> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody ChangePasswordRequest request) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AuthenticationException("MISSING_TOKEN", "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new AuthenticationException("EMPTY_TOKEN", "Token is empty");
        }

        AuthResponse response = authService.changePassword(token, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for auth service.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "security-service"
        ));
    }
}
