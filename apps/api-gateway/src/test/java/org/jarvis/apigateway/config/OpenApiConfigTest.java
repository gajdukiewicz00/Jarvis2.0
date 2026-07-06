package org.jarvis.apigateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void apiGatewayOpenApiExposesTitleAndVersion() {
        OpenAPI openApi = config.apiGatewayOpenApi();

        assertThat(openApi.getInfo()).isNotNull();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("Jarvis API Gateway");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void agentServiceProxyGroupMatchesOnlyTheAgentsProxyRoutes() {
        GroupedOpenApi group = config.agentServiceProxyGroup();

        assertThat(group.getGroup()).isEqualTo("agent-service");
        assertThat(group.getPathsToMatch()).containsExactly("/api/v1/agents/**");
    }

    @Test
    void mediaServiceProxyGroupMatchesOnlyTheMediaProxyRoutes() {
        GroupedOpenApi group = config.mediaServiceProxyGroup();

        assertThat(group.getGroup()).isEqualTo("media-service");
        assertThat(group.getPathsToMatch()).containsExactly("/api/v1/media/**");
    }
}
