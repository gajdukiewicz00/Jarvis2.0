package org.jarvis.planner.config;

import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RestClientConfig {
    
    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            ServiceJwtProvider serviceJwtProvider,
            @Value("${spring.application.name}") String serviceName) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(5))
            // The 14B brain routinely takes >10s for non-trivial generations
            // (generate-document, daily plans). A 10s read-timeout turned those
            // into spurious 503 LLM_UNAVAILABLE while short prompts passed,
            // masking the issue in smoke tests. 60s matches the orchestrator's
            // own LLM budget headroom.
            .setReadTimeout(Duration.ofSeconds(60))
            .additionalInterceptors((request, body, execution) -> {
                request.getHeaders().set(
                        ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                        serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
                return execution.execute(request, body);
            })
            .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
