package org.jarvis.common.feign;

import feign.RequestInterceptor;
import org.jarvis.common.security.GatewayAuthFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Auto-configures a Feign {@link RequestInterceptor} that adds service-to-service
 * authentication headers to outbound Feign calls.
 *
 * <p>Only activates when OpenFeign is on the classpath. Services that already define
 * their own {@code serviceAuthInterceptor} bean (e.g. api-gateway) are not affected
 * thanks to {@code @ConditionalOnMissingBean}.</p>
 *
 * <p>Headers added:</p>
 * <ul>
 *   <li>{@code X-Service-Token} — signed service JWT from {@link ServiceJwtProvider}</li>
 *   <li>{@code X-User-Id} — delegated user identity from SecurityContext</li>
 *   <li>{@code X-User-Roles} — delegated user roles from SecurityContext</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class ServiceFeignAutoConfiguration {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Bean
    @ConditionalOnMissingBean(name = "serviceAuthInterceptor")
    public RequestInterceptor serviceAuthInterceptor(
            ServiceJwtProvider serviceJwtProvider,
            @Value("${spring.application.name:unknown}") String serviceName) {
        return template -> {
            if (serviceJwtProvider.isEnabled() && !template.headers().containsKey(SERVICE_TOKEN_HEADER)) {
                String token = serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL"));
                template.header(SERVICE_TOKEN_HEADER, token);
            }

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return;
            }

            if (!template.headers().containsKey(GatewayAuthFilter.USER_ID_HEADER)) {
                template.header(GatewayAuthFilter.USER_ID_HEADER, authentication.getName());
            }
            if (!template.headers().containsKey(GatewayAuthFilter.USER_ROLES_HEADER)) {
                String roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.joining(","));
                if (!roles.isBlank()) {
                    template.header(GatewayAuthFilter.USER_ROLES_HEADER, roles);
                }
            }
        };
    }
}
