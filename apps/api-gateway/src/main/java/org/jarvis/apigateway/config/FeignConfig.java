package org.jarvis.apigateway.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign configuration to propagate user context and trace IDs
 */
@Slf4j
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                        .getRequestAttributes();

                if (attributes != null) {
                    var request = attributes.getRequest();

                    // Propagate user context headers
                    String userId = request.getHeader("X-User-Id");
                    String username = request.getHeader("X-Username");
                    String userRoles = request.getHeader("X-User-Roles");
                    String userRole = request.getHeader("X-User-Role");

                    log.debug("FeignConfig: Propagating headers - userId: {}, username: {}, roles: {}, role: {}",
                            userId, username, userRoles, userRole);

                    if (userId != null) {
                        template.header("X-User-Id", userId);
                        log.debug("FeignConfig: Added X-User-Id header: {}", userId);
                    }
                    if (username != null) {
                        template.header("X-Username", username);
                        log.debug("FeignConfig: Added X-Username header: {}", username);
                    }
                    if (userRoles != null) {
                        template.header("X-User-Roles", userRoles);
                        log.debug("FeignConfig: Added X-User-Roles header: {}", userRoles);
                    }
                    if (userRole != null) {
                        template.header("X-User-Role", userRole);
                        log.debug("FeignConfig: Added X-User-Role header: {}", userRole);
                    }

                    // Propagate trace ID from MDC
                    String traceId = MDC.get("traceId");
                    if (traceId != null) {
                        template.header("X-Trace-Id", traceId);
                        log.info("Propagating traceId {} to {}", traceId, template.url());
                    } else {
                        log.info("No traceId found, but request is to: {}", template.url());
                    }
                } else {
                    log.warn("FeignConfig: RequestContextHolder.getRequestAttributes() returned null");
                }

                // Always add default headers if none provided (for whitelisted requests)
                if (!template.headers().containsKey("X-User-Id")) {
                    log.warn("FeignConfig: No X-User-Id header found, adding defaults for whitelisted request");
                    template.header("X-User-Id", "dev-user");
                    template.header("X-Username", "dev-user");
                    template.header("X-User-Role", "USER");
                }
            }
        };
    }
}
