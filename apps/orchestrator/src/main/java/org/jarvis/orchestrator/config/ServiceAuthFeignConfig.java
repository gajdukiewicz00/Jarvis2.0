package org.jarvis.orchestrator.config;

import feign.RequestInterceptor;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ServiceAuthFeignConfig {

    @Bean
    public RequestInterceptor serviceAuthInterceptor(ServiceJwtProvider serviceJwtProvider,
                                                     @Value("${spring.application.name:orchestrator}") String serviceName) {
        return template -> template.header(
                "Authorization",
                "Bearer " + serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
    }
}
