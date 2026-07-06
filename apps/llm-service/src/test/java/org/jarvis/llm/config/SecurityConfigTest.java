package org.jarvis.llm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void publicEndpointsPermitActuatorPrometheusAndHealthWithoutAuthentication() {
        String[] endpoints = config.getPublicEndpoints();

        assertThat(endpoints).containsExactlyInAnyOrder(
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/info",
                "/actuator/prometheus",
                "/api/v1/llm/health"
        );
    }
}
