package org.jarvis.analytics.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ensures analytics requests carry delegated user context after service JWT validation.
 */
@Slf4j
public class TokenValidationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing authentication\"}");
            return;
        }

        boolean serviceOnlyAuthentication = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("SVC_INTERNAL"::equals);
        String delegatedUserId = request.getHeader(USER_ID_HEADER);
        String userId = delegatedUserId != null && !delegatedUserId.isBlank() ? delegatedUserId : authentication.getName();
        if (serviceOnlyAuthentication) {
            if (delegatedUserId == null || delegatedUserId.isBlank()) {
                log.warn("Analytics request to {} is missing delegated user context", path);
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Missing X-User-Id delegated user context\"}");
                return;
            }
        }

        MDC.put("userId", userId);
        String requestRoles = request.getHeader(USER_ROLES_HEADER);
        MDC.put("userRoles", requestRoles != null ? requestRoles : authentication.getAuthorities().toString());

        try {
            log.debug("Processing analytics request for user: {}", userId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("userRoles");
        }
    }
}
