package org.jarvis.pccontrol.filter;

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
import java.util.Arrays;
import java.util.List;

/**
 * Filter to validate user context and authorize PC control commands.
 * Disabled in dev profile where security is relaxed.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@Profile("!dev")
public class TokenValidationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";
    private static final List<String> ADMIN_ONLY_PATHS = Arrays.asList(
            "/api/v1/pc/shutdown",
            "/api/v1/pc/restart");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String userId = request.getHeader(USER_ID_HEADER);
        String roles = request.getHeader(USER_ROLES_HEADER);
        String path = request.getRequestURI();

        // Allow actuator
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate user context
        if (userId == null || userId.isEmpty()) {
            log.warn("Unauthorized PC control attempt to {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing user authentication\"}");
            return;
        }

        // Check admin-only commands
        if (isAdminOnlyPath(path) && !hasAdminRole(roles)) {
            log.warn("User {} attempted admin command: {}", userId, path);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Admin privileges required\"}");
            return;
        }

        MDC.put("userId", userId);
        if (roles != null) {
            MDC.put("userRoles", roles);
        }

        try {
            log.debug("Processing PC control for user: {}", userId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("userRoles");
        }
    }

    private boolean isAdminOnlyPath(String path) {
        return ADMIN_ONLY_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean hasAdminRole(String roles) {
        return roles != null && (roles.contains("ADMIN") || roles.contains("SUPER_ADMIN"));
    }
}
