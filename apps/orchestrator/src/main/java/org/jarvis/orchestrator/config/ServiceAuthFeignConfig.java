package org.jarvis.orchestrator.config;

import feign.RequestInterceptor;
import org.jarvis.common.JarvisHttpHeaders;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

@Configuration
public class ServiceAuthFeignConfig {

    @Bean
    public RequestInterceptor serviceAuthInterceptor(ServiceJwtProvider serviceJwtProvider,
                                                     @Value("${spring.application.name:orchestrator}") String serviceName) {
        return template -> {
            template.header(
                    ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                    serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));

            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                propagateIfPresent(attrs, template, JarvisHttpHeaders.MODEL_PROFILE);
                propagateIfPresent(attrs, template, JarvisHttpHeaders.CORRELATION_ID);
                propagateIfPresent(attrs, template, JarvisHttpHeaders.USER_ID);
            }
        };
    }

    private static void propagateIfPresent(ServletRequestAttributes attrs,
                                           feign.RequestTemplate template,
                                           String headerName) {
        String value = attrs.getRequest().getHeader(headerName);
        if (value != null && !value.isBlank()) {
            template.header(headerName, value);
        }
    }
}
