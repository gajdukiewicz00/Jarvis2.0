package org.jarvis.security.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;
import org.jarvis.security.config.GlobalExceptionHandler.UserAlreadyExistsException;
import org.jarvis.security.dto.AuthResponse;
import org.jarvis.security.dto.LoginRequest;
import org.jarvis.security.dto.RefreshRequest;
import org.jarvis.security.dto.RegisterRequest;
import org.jarvis.security.model.User;
import org.jarvis.security.service.AuthService;
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
 * - GET /auth/me - Get current user info
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
