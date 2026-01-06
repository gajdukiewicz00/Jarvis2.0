package org.jarvis.analytics.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to validate user context headers from API Gateway.
 * Disabled in dev profile where security is relaxed.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2) // After TracePropagationFilter
@Profile("!dev")
public class TokenValidationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Extract user context from headers (set by API Gateway)
        String userId = request.getHeader(USER_ID_HEADER);
        String roles = request.getHeader(USER_ROLES_HEADER);

        // Allow actuator endpoints without auth
        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Reject requests without user context
        if (userId == null || userId.isEmpty()) {
            log.warn("Request to {} without user context", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing user authentication\"}");
            return;
        }

        // Add user context to MDC for logging
        MDC.put("userId", userId);
        if (roles != null) {
            MDC.put("userRoles", roles);
        }

        try {
            log.debug("Processing request for user: {} with roles: {}", userId, roles);
            filterChain.doFilter(request, response);
        } finally {
            // Clean up MDC
            MDC.remove("userId");
            MDC.remove("userRoles");
        }
    }
}
