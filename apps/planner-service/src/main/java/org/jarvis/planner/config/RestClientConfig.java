package org.jarvis.planner.config;

import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
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
            .setReadTimeout(Duration.ofSeconds(10))
            .additionalInterceptors((request, body, execution) -> {
                request.getHeaders().set(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
                return execution.execute(request, body);
            })
            .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
