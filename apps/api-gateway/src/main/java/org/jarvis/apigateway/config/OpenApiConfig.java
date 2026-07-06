package org.jarvis.apigateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi wiring for the gateway (api-gateway hardening #8).
 * <p>
 * Exposes the aggregate {@code /v3/api-docs} + {@code /swagger-ui/index.html} for every
 * controller in this module, plus two focused {@link GroupedOpenApi} groups so the
 * agent-service and media-service proxy routes — added earlier and hardened here — are easy
 * to find without paging through every other proxied service.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI apiGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Jarvis API Gateway")
                        .description("Public HTTP surface for the Jarvis platform: authentication, voice, "
                                + "NLP, orchestration, planning, agents, the media pipeline, and the other "
                                + "downstream services this gateway proxies.")
                        .version("1.0.0"));
    }

    @Bean
    public GroupedOpenApi agentServiceProxyGroup() {
        return GroupedOpenApi.builder()
                .group("agent-service")
                .displayName("Agent Service (roles, tasks, swarm runs)")
                .pathsToMatch("/api/v1/agents/**")
                .build();
    }

    @Bean
    public GroupedOpenApi mediaServiceProxyGroup() {
        return GroupedOpenApi.builder()
                .group("media-service")
                .displayName("Media Service (probe, transcode/dub pipeline, job artifacts)")
                .pathsToMatch("/api/v1/media/**")
                .build();
    }
}
