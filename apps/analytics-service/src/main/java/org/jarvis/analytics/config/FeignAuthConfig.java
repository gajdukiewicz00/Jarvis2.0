package org.jarvis.analytics.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.stream.Collectors;

/**
 * Propagates the caller's JWT from the current security context to downstream Feign requests.
 */
@Configuration
public class FeignAuthConfig {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";

    @Bean
    public RequestInterceptor authFeignInterceptor() {
        return template -> {
            String token = resolveToken();
            if (StringUtils.hasText(token)) {
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }

            String userId = resolveUserId();
            if (StringUtils.hasText(userId)) {
                template.header(USER_ID_HEADER, userId);
            }

            String roles = resolveUserRoles();
            if (StringUtils.hasText(roles)) {
                template.header(USER_ROLES_HEADER, roles);
            }
        };
    }

    private String resolveToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            String token = extractFromAuthentication(authentication);
            if (StringUtils.hasText(token)) {
                return token;
            }
        }

        HttpServletRequest request = currentRequest();
        if (request != null) {
            return extractBearerValue(request.getHeader(HttpHeaders.AUTHORIZATION));
        }

        return null;
    }

    private String resolveUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof String userId && StringUtils.hasText(userId)) {
                return userId;
            }
            if (principal != null) {
                String principalValue = principal.toString();
                if (StringUtils.hasText(principalValue) && !"anonymousUser".equals(principalValue)) {
                    return principalValue;
                }
            }
        }

        HttpServletRequest request = currentRequest();
        if (request != null) {
            return request.getHeader(USER_ID_HEADER);
        }

        return null;
    }

    private String resolveUserRoles() {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            String headerRoles = request.getHeader(USER_ROLES_HEADER);
            if (StringUtils.hasText(headerRoles)) {
                return headerRoles;
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            String authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && !authority.isBlank())
                    .filter(authority -> !"SVC_INTERNAL".equals(authority))
                    .collect(Collectors.joining(","));
            if (StringUtils.hasText(authorities)) {
                return authorities;
            }
        }

        return null;
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String extractFromAuthentication(Authentication authentication) {
        String credentialsToken = extractBearerValue(authentication.getCredentials());
        if (StringUtils.hasText(credentialsToken)) {
            return credentialsToken;
        }

        String principalToken = extractFromPrincipal(authentication.getPrincipal());
        if (StringUtils.hasText(principalToken)) {
            return principalToken;
        }

        return null;
    }

    private String extractFromPrincipal(Object principal) {
        if (principal == null) {
            return null;
        }

        if (principal instanceof JwtTokenSupplier supplier) {
            return extractBearerValue(supplier.getToken());
        }

        try {
            Method getToken = principal.getClass().getMethod("getToken");
            Object value = getToken.invoke(principal);
            return extractBearerValue(value);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException | SecurityException ex) {
            // Swallow quietly – lack of token should not break downstream calls
            return null;
        }
    }

    private String extractBearerValue(Object candidate) {
        if (!(candidate instanceof String token) || !StringUtils.hasText(token)) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.startsWith("Bearer ")) {
            return trimmed.substring(7);
        }
        return trimmed;
    }

    /**
     * Optional contract for principals that expose the JWT directly.
     */
    public interface JwtTokenSupplier {
        String getToken();
    }
}
