package org.jarvis.apigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * JWT Authentication Filter for api-gateway.
 * 
 * Validates JWT tokens and adds user context headers for downstream services.
 * Handles different JWT error types with appropriate responses.
 */
@Slf4j
@Component
public class JwtFilter extends OncePerRequestFilter {

    @Value("${jwt.enabled:false}")
    private boolean jwtEnabled;

    @Value("${jwt.secret}")
    private String jwtSecret;

    // Public endpoints that don't require JWT
    private static final List<String> WHITELIST = List.of(
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/auth/health",
            "/api/security/auth/login",
            "/api/security/auth/register",
            "/actuator/", // All actuator endpoints
            "/internal/", // Internal service-to-service communication (PC control)
            "/ws/", // WebSocket endpoints (PC control handshake)
            "/health",
            "/favicon.ico");
    private static final String TOOL_PATH_PREFIX = "/api/v1/tools/";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /**
     * Check if the request path is whitelisted (public endpoint)
     */
    private boolean isWhitelisted(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Check whitelist patterns
        boolean whitelisted = WHITELIST.stream().anyMatch(path::startsWith);

        return whitelisted;
    }

    private boolean isInternalToolRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith(TOOL_PATH_PREFIX)) {
            return false;
        }
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        return forwardedFor == null || forwardedFor.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // JWT disabled - skip validation entirely
        if (!jwtEnabled) {
            log.trace("JwtFilter: JWT disabled, passing through");
            filterChain.doFilter(request, response);
            return;
        }

        if (isInternalToolRequest(request)) {
            log.trace("JwtFilter: Internal tool request bypassing JWT");
            filterChain.doFilter(request, response);
            return;
        }

        // Check if path is whitelisted (public endpoint) - MUST be first check
        if (isWhitelisted(request)) {
            // Add default user headers for whitelisted requests (for development)
            HttpServletRequest wrappedRequest = wrapRequestWithUserHeaders(request, "dev-user", "dev-user", "USER");
            log.trace("JwtFilter: Whitelisted path {}", path);
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        log.debug("JwtFilter: Path {} requires JWT validation", path);

        // Extract JWT from Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN",
                    "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "EMPTY_TOKEN",
                    "Token is empty");
            return;
        }

        try {
            // Validate JWT
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Extract user info from JWT
            String userId = claims.getSubject();
            String username = (String) claims.get("username");
            String role = claims.get("role") != null ? claims.get("role").toString() : "USER";

            // Add user info to request attributes
            request.setAttribute("userId", userId);
            request.setAttribute("username", username);
            request.setAttribute("roles", role);

            log.debug("JWT validated for user: {} (ID: {})", username, userId);

            // Wrap request to add headers for downstream microservices
            HttpServletRequest wrappedRequest = wrapRequestWithUserHeaders(request, userId, username, role);
            filterChain.doFilter(wrappedRequest, response);

        } catch (ExpiredJwtException e) {
            // Token expired - this is expected, log at INFO/DEBUG level
            log.info("JWT expired for subject: {}", e.getClaims() != null ? e.getClaims().getSubject() : "unknown");
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED",
                    "JWT token has expired");

        } catch (SignatureException e) {
            // Invalid signature - potential security issue
            log.warn("JWT signature validation failed: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "Invalid token signature");

        } catch (MalformedJwtException e) {
            // Malformed token
            log.warn("Malformed JWT token: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "MALFORMED_TOKEN",
                    "Malformed JWT token");

        } catch (IllegalArgumentException e) {
            // Empty or null token
            log.warn("Invalid JWT argument: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Invalid token format");

        } catch (Exception e) {
            // Other errors - log as error for investigation
            log.error("Unexpected JWT validation error: {}", e.getMessage(), e);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "JWT_ERROR",
                    "An error occurred validating the token");
        }
    }

    /**
     * Send a structured JSON error response.
     */
    private void sendErrorResponse(HttpServletResponse response, HttpStatus status,
            String errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String jsonResponse = String.format(
                "{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"service\":\"api-gateway\"}",
                LocalDateTime.now().toString(),
                status.value(),
                errorCode,
                message);

        response.getWriter().write(jsonResponse);
    }

    /**
     * Wrap request to add user context headers for downstream services.
     */
    private HttpServletRequest wrapRequestWithUserHeaders(HttpServletRequest request,
            String userId, String username, String role) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if ("X-User-Id".equalsIgnoreCase(name)) {
                    return userId;
                }
                if ("X-Username".equalsIgnoreCase(name)) {
                    return username;
                }
                if ("X-User-Roles".equalsIgnoreCase(name)) {
                    return role;
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> headers = new HashSet<>(Collections.list(super.getHeaderNames()));
                headers.addAll(Arrays.asList("X-User-Id", "X-Username", "X-User-Roles"));
                return Collections.enumeration(headers);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if ("X-User-Id".equalsIgnoreCase(name)) {
                    return Collections.enumeration(Collections.singletonList(userId));
                }
                if ("X-Username".equalsIgnoreCase(name)) {
                    return Collections.enumeration(Collections.singletonList(username));
                }
                if ("X-User-Roles".equalsIgnoreCase(name)) {
                    return Collections.enumeration(Collections.singletonList(role));
                }
                return super.getHeaders(name);
            }
        };
    }
}
