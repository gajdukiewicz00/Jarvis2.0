package org.jarvis.lifetracker.config;

import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * RestTemplate that attaches a service JWT (SVC_INTERNAL) so life-tracker can
 * call other services' /internal/* endpoints (e.g. voice-gateway /internal/voice/notify
 * for proactive spoken warnings). Mirrors planner-service's RestClientConfig.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate serviceRestTemplate(
            RestTemplateBuilder builder,
            ServiceJwtProvider serviceJwtProvider,
            @Value("${spring.application.name}") String serviceName) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(8))
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().set(
                            ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                            serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
                    return execution.execute(request, body);
                })
                .build();
    }
}
