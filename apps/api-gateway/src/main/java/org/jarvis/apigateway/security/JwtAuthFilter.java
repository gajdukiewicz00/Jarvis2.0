package org.jarvis.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Unified JWT authentication filter for API Gateway.
 * <p>
 * Merges the responsibilities of the former {@code JwtFilter} (gatekeeper, headers,
 * kill switch, error responses) and {@code JwtAuthenticationFilter} (SecurityContext).
 * <p>
 * Single token parse via {@link JwtUtil} — one source of truth.
 * <ul>
 *   <li>Kill switch: {@code jarvis.jwt.enabled=false} → pass through</li>
 *   <li>Public paths (actuator/health) → pass through</li>
 *   <li>Sets {@link org.springframework.security.core.context.SecurityContext}</li>
 *   <li>Wraps request with {@code X-User-*} headers for downstream services</li>
 *   <li>Returns structured JSON 401 for all JWT error cases</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Value("${jarvis.jwt.enabled:false}")
    private boolean jwtEnabled;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/health/");

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Kill switch: JWT disabled → pass through
        if (!jwtEnabled) {
            log.trace("JwtAuthFilter: JWT disabled, passing through");
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // Public endpoints: skip JWT validation
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("JwtAuthFilter: path {} requires JWT validation", path);

        // ── Extract Authorization header ──────────────────────────────
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

        // ── Single parse via JwtUtil ──────────────────────────────────
        try {
            Claims claims = jwtUtil.validateToken(token);

            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            List<String> roles = extractRoles(claims);
            String rolesString = String.join(",", roles);

            // 1. Set Spring Security context
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }

            // 2. Set request attributes
            request.setAttribute("userId", userId);
            request.setAttribute("username", username);
            request.setAttribute("roles", rolesString);

            log.debug("JwtAuthFilter: authenticated user {} (ID: {}), roles: [{}]",
                    username, userId, rolesString);

            // 3. Wrap request with X-User-* headers for downstream services
            HttpServletRequest wrappedRequest = wrapRequestWithUserHeaders(
                    request, userId, username, rolesString);
            filterChain.doFilter(wrappedRequest, response);

        } catch (ExpiredJwtException e) {
            log.info("JwtAuthFilter: JWT expired for subject: {}",
                    e.getClaims() != null ? e.getClaims().getSubject() : "unknown");
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED",
                    "JWT token has expired");

        } catch (SignatureException e) {
            log.warn("JwtAuthFilter: JWT signature validation failed: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "Invalid token signature");

        } catch (MalformedJwtException e) {
            log.warn("JwtAuthFilter: Malformed JWT token: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "MALFORMED_TOKEN",
                    "Malformed JWT token");

        } catch (IllegalArgumentException e) {
            log.warn("JwtAuthFilter: Invalid JWT argument: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Invalid token format");

        } catch (RuntimeException e) {
            log.error("JwtAuthFilter: Unexpected JWT validation error: {}", e.getMessage(), e);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "JWT_ERROR",
                    "An error occurred validating the token");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Send a structured JSON error response (preserves JwtFilter error format).
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
            String userId, String username, String roles) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if ("X-User-Id".equalsIgnoreCase(name)) return userId;
                if ("X-Username".equalsIgnoreCase(name)) return username;
                if ("X-User-Roles".equalsIgnoreCase(name)) return roles;
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> headers = new HashSet<>(Collections.list(super.getHeaderNames()));
                headers.addAll(List.of("X-User-Id", "X-Username", "X-User-Roles"));
                return Collections.enumeration(headers);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if ("X-User-Id".equalsIgnoreCase(name))
                    return Collections.enumeration(Collections.singletonList(userId));
                if ("X-Username".equalsIgnoreCase(name))
                    return Collections.enumeration(Collections.singletonList(username));
                if ("X-User-Roles".equalsIgnoreCase(name))
                    return Collections.enumeration(Collections.singletonList(roles));
                return super.getHeaders(name);
            }
        };
    }

    /**
     * Extract roles from JWT claims as a deduplicated list of strings.
     * Checks "roles" claim (Collection or comma-separated String),
     * then singular "role" claim, defaults to "USER".
     */
    private List<String> extractRoles(Claims claims) {
        List<String> roles = new ArrayList<>();

        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof Collection<?> roleList) {
            for (Object role : roleList) {
                if (role != null) {
                    String trimmed = role.toString().trim();
                    if (!trimmed.isEmpty()) {
                        roles.add(trimmed);
                    }
                }
            }
        } else if (rolesClaim instanceof String rolesString) {
            for (String role : rolesString.split(",")) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty()) {
                    roles.add(trimmed);
                }
            }
        }

        // Fallback: singular "role" claim
        String roleClaim = claims.get("role", String.class);
        if (roleClaim != null && !roleClaim.isBlank()) {
            roles.add(roleClaim.trim());
        }

        if (roles.isEmpty()) {
            roles.add("USER");
        }

        return roles.stream().distinct().toList();
    }
}
