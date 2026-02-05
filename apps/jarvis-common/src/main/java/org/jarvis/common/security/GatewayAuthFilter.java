package org.jarvis.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter that propagates user identity for gateway-service calls.
 * Reads X-User-Id and X-User-Roles headers only when a valid service token is already present.
 */
@Slf4j
public class GatewayAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth == null || !existingAuth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader(USER_ID_HEADER);
        String roles = request.getHeader(USER_ROLES_HEADER);

        if (userId != null && !userId.isBlank() && isServiceAuthentication(existingAuth)) {
            List<SimpleGrantedAuthority> delegatedRoles = parseRoles(roles);
            Set<SimpleGrantedAuthority> merged = mergeAuthorities(existingAuth.getAuthorities(), delegatedRoles);

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    merged
            );
            auth.setDetails("delegated-by:" + existingAuth.getName());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("Delegated user from gateway: userId={}, roles={}, service={}",
                    userId, roles, existingAuth.getName());
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

    private boolean isServiceAuthentication(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("SVC_INTERNAL"::equals);
    }

    private Set<SimpleGrantedAuthority> mergeAuthorities(Collection<? extends GrantedAuthority> base,
                                                         List<SimpleGrantedAuthority> extra) {
        Set<SimpleGrantedAuthority> merged = new LinkedHashSet<>();
        if (base != null) {
            for (GrantedAuthority authority : base) {
                if (authority != null && authority.getAuthority() != null) {
                    merged.add(new SimpleGrantedAuthority(authority.getAuthority()));
                }
            }
        }
        if (extra != null) {
            merged.addAll(extra);
        }
        return merged;
    }
}
