package org.jarvis.apigateway.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.AuthClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Proxy controller for authentication endpoints.
 * Proxies requests to security-service.
 */
@Slf4j
@RestController
@RequestMapping({ "/auth", "/api/v1/security/auth" })
public class AuthProxyController implements org.springframework.beans.factory.InitializingBean {

    private final AuthClient authClient;

    @org.springframework.beans.factory.annotation.Value("${services.security.url}")
    private String securityServiceUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterPropertiesSet() {
        log.info("AuthProxyController initialized with securityServiceUrl: {}", securityServiceUrl);
    }

    public AuthProxyController(AuthClient authClient) {
        this.authClient = authClient;
        log.info("🔧🔧🔧 AuthProxyController: Controller initialized! AuthClient: {}",
                authClient != null ? "FOUND" : "NULL");
    }

    /**
     * Register a new user
     * POST /auth/register
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request) {
        log.info("🎯 AuthProxyController.register() - User: {}", request.get("username"));
        try {
            ResponseEntity<Map<String, Object>> response = authClient.register(request);
            log.info("✅ Registration successful for: {}", request.get("username"));
            return sanitizeUpstreamResponse(response);
        } catch (FeignException e) {
            return handleFeignError(e, "register");
        }
    }

    /**
     * Login and get JWT tokens
     * POST /auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request) {
        log.info("🔐 Proxying POST /auth/login for user: {}", request.get("username"));
        try {
            return sanitizeUpstreamResponse(authClient.login(request));
        } catch (FeignException e) {
            return handleFeignError(e, "login");
        }
    }

    /**
     * Refresh access token using refresh token
     * POST /auth/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, Object> request) {
        log.info("🔄 Proxying POST /auth/refresh");
        try {
            return sanitizeUpstreamResponse(authClient.refresh(request));
        } catch (FeignException e) {
            return handleFeignError(e, "refresh");
        }
    }

    /**
     * Get current authenticated user info.
     * GET /auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("Proxying GET /auth/me");
        try {
            return sanitizeUpstreamResponse(authClient.me(authorization));
        } catch (FeignException e) {
            return handleFeignError(e, "me");
        }
    }

    private ResponseEntity<Map<String, Object>> sanitizeUpstreamResponse(ResponseEntity<Map<String, Object>> upstream) {
        return ResponseEntity.status(upstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(upstream.getBody());
    }

    /**
     * Handle Feign client exceptions and forward error responses.
     * Does NOT log secrets/JWT tokens.
     */
    private ResponseEntity<Map<String, Object>> handleFeignError(FeignException e, String operation) {
        HttpStatus status = HttpStatus.resolve(e.status());
        String content = e.contentUTF8();
        
        // Log error without sensitive data (no JWT/secrets in logs)
        log.warn("Auth operation '{}' failed with status {} (content length: {})", 
                operation, e.status(), content != null ? content.length() : 0);

        // Try to forward JSON body if present
        if (content != null && !content.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(content, Map.class);
                // Remove sensitive fields from response body if present
                body.remove("accessToken");
                body.remove("refreshToken");
                body.remove("token");
                
                // Determine appropriate HTTP status
                HttpStatus responseStatus = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
                
                // Map common error statuses
                if (e.status() == 401) {
                    responseStatus = HttpStatus.UNAUTHORIZED;
                } else if (e.status() == 409) {
                    responseStatus = HttpStatus.CONFLICT;
                } else if (e.status() >= 400 && e.status() < 500) {
                    responseStatus = HttpStatus.BAD_REQUEST;
                }
                
                return ResponseEntity.status(responseStatus)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            } catch (JsonProcessingException parseEx) {
                log.warn("Failed to parse error body from security-service: {}", parseEx.getMessage());
            }
        }

        // Default error response
        HttpStatus responseStatus = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        if (e.status() == 401) {
            responseStatus = HttpStatus.UNAUTHORIZED;
        } else if (e.status() == 409) {
            responseStatus = HttpStatus.CONFLICT;
        }
        
        return ResponseEntity.status(responseStatus)
                .body(Map.of("error", "Authentication service error", "operation", operation));
    }
}
