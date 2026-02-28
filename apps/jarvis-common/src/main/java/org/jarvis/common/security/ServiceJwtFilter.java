package org.jarvis.common.security;

import io.jsonwebtoken.Claims;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filter that validates service-to-service JWT tokens (X-Service-Token header).
 *
 * <p>Registered as a bean by {@link org.jarvis.common.JarvisCommonAutoConfiguration}.</p>
 */
@Slf4j
public class ServiceJwtFilter extends OncePerRequestFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private final ServiceJwtProvider jwtProvider;

    public ServiceJwtFilter(ServiceJwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (jwtProvider.isEnabled() && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null) {
                Claims claims = jwtProvider.parseServiceClaims(token);
                if (claims != null) {
                    String subject = claims.getSubject();
                    List<SimpleGrantedAuthority> authorities = extractAuthorities(claims);
                    if (authorities.isEmpty()) {
                        authorities.add(new SimpleGrantedAuthority("SVC_INTERNAL"));
                    }
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            subject,
                            null,
                            authorities);
                    auth.setDetails("service-jwt");
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    log.debug("Service JWT rejected for request {}", request.getRequestURI());
                }
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(SERVICE_TOKEN_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private List<SimpleGrantedAuthority> extractAuthorities(Claims claims) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof String rolesString) {
            for (String role : rolesString.split(",")) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty()) {
                    authorities.add(new SimpleGrantedAuthority(trimmed));
                }
            }
        } else if (rolesClaim instanceof Collection<?> roleList) {
            for (Object role : roleList) {
                if (role != null) {
                    String trimmed = role.toString().trim();
                    if (!trimmed.isEmpty()) {
                        authorities.add(new SimpleGrantedAuthority(trimmed));
                    }
                }
            }
        }
        return authorities;
    }
}
