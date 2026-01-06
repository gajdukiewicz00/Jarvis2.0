package org.jarvis.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Filter that authenticates requests coming through API Gateway.
 * Reads X-User-Id and X-User-Roles headers set by the gateway after JWT validation.
 * 
 * This filter should be used by all internal microservices that are behind the gateway.
 */
@Slf4j
public class GatewayAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String userId = request.getHeader(USER_ID_HEADER);
        String roles = request.getHeader(USER_ROLES_HEADER);

        if (userId != null && !userId.isBlank()) {
            List<SimpleGrantedAuthority> authorities = parseRoles(roles);
            
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId, 
                    null,
                    authorities
            );
            auth.setDetails(roles);
            SecurityContextHolder.getContext().setAuthentication(auth);
            
            log.debug("Authenticated user from gateway: userId={}, roles={}", userId, roles);
        }

        chain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}

