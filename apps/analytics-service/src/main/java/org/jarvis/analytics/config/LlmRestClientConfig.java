package org.jarvis.analytics.config;

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
 * RestTemplate for the guarded NL-analytics -> llm-service call (llm-service
 * isn't behind Feign like life-tracker; it speaks a plain chat-style REST
 * contract, mirroring planner-service's LlmServiceClient setup).
 */
@Configuration
public class LlmRestClientConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            ServiceJwtProvider serviceJwtProvider,
            @Value("${spring.application.name}") String serviceName) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                // Non-trivial generations on the host GPU brain routinely take >10s.
                .setReadTimeout(Duration.ofSeconds(60))
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().set(
                            ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                            serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
                    return execution.execute(request, body);
                })
                .build();
    }
}
