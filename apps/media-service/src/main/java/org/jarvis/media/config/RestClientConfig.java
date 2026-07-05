package org.jarvis.media.config;

import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Shared outbound HTTP client for optional service-to-service calls. Currently
 * used only by {@link org.jarvis.media.subtitle.LlmTranslationProvider} when
 * {@code media.translation.mode=llm}; the bean (and therefore any outbound network
 * dependency) does not exist at all while translation stays on its {@code mock}
 * default. Every outbound request carries a short-lived SVC_INTERNAL service JWT
 * so the callee (llm-service) accepts it as an internal call.
 */
@Configuration
@ConditionalOnProperty(name = "media.translation.mode", havingValue = "llm")
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            ServiceJwtProvider serviceJwtProvider,
            @Value("${spring.application.name}") String serviceName) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .additionalInterceptors((request, body, execution) -> {
                    if (serviceJwtProvider.isEnabled()) {
                        request.getHeaders().set(
                                ServiceJwtFilter.SERVICE_TOKEN_HEADER,
                                serviceJwtProvider.createToken(serviceName, List.of("SVC_INTERNAL")));
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
