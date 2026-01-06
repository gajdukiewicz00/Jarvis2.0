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
                String username = claims.getSubject();

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    // Extract roles from claims (if present)
                    List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));

                    // Create Spring Security authentication token
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            authorities);

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // Add user context as request attributes for downstream services
                    request.setAttribute("userId", username);
                    request.setAttribute("username", username);

                    log.debug("JWT authenticated user: {}", username);
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
}
