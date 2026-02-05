package org.jarvis.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JWT Authentication Filter for API Gateway.
 * Validates JWT tokens locally (no network call to security-service) and sets
 * Spring Security context.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        log.debug("JwtAuthenticationFilter: Processing request to path: {}", path);

        // Extract JWT from Authorization header
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                // Validate token locally (no network call)
                Claims claims = jwtUtil.validateToken(token);
                String userId = claims.getSubject();
                String username = claims.get("username", String.class);

                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<SimpleGrantedAuthority> authorities = extractAuthorities(claims);

                    // Create Spring Security authentication token
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            authorities);

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // Add user context as request attributes for downstream services
                    request.setAttribute("userId", userId);
                    if (username != null) {
                        request.setAttribute("username", username);
                    }

                    log.debug("JWT authenticated user: {}", userId);
                }
            } catch (ExpiredJwtException e) {
                // Expected case - token expired, log at debug level
                log.debug("JWT token expired for request to {}", path);
                // Don't set authentication - Spring Security will handle as unauthenticated
            } catch (JwtException e) {
                // Other JWT errors - log at warn level
                log.warn("JWT validation failed for {}: {}", path, e.getMessage());
                // Don't set authentication - Spring Security will handle as unauthenticated
            }
        }

        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> extractAuthorities(Claims claims) {
        List<String> roles = new ArrayList<>();
        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof String rolesString) {
            for (String role : rolesString.split(",")) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty()) {
                    roles.add(trimmed);
                }
            }
        } else if (rolesClaim instanceof Collection<?> roleList) {
            for (Object role : roleList) {
                if (role != null) {
                    String trimmed = role.toString().trim();
                    if (!trimmed.isEmpty()) {
                        roles.add(trimmed);
                    }
                }
            }
        }

        String role = claims.get("role", String.class);
        if (role != null && !role.isBlank()) {
            roles.add(role.trim());
        }

        if (roles.isEmpty()) {
            roles.add("USER");
        }

        return roles.stream()
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
