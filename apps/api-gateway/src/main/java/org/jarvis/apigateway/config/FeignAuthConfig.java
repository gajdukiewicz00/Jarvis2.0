package org.jarvis.apigateway.config;

import feign.RequestInterceptor;
import org.jarvis.common.security.GatewayAuthFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class FeignAuthConfig {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Bean
    @ConditionalOnMissingBean(ServiceJwtProvider.class)
    public ServiceJwtProvider serviceJwtProvider(
            @Value("${service.jwt.secret:}") String serviceSecret,
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${service.jwt.issuer:jarvis-internal}") String issuer,
            @Value("${service.jwt.audience:jarvis-services}") String audience,
            @Value("${service.jwt.ttl-seconds:300}") long ttlSeconds,
            @Value("${service.jwt.required:true}") boolean required) {
        return new ServiceJwtProvider(serviceSecret, jwtSecret, issuer, audience, ttlSeconds, required);
    }

    @Bean
    public RequestInterceptor serviceAuthInterceptor(ServiceJwtProvider serviceJwtProvider,
                                                     @Value("${spring.application.name:api-gateway}") String serviceName) {
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
