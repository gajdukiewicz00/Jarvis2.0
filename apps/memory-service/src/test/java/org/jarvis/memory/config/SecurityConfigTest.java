package org.jarvis.memory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies memory-service's public actuator endpoints permit unauthenticated
 * scraping (Prometheus + health probes) while leaving every other endpoint —
 * including the {@code /api/v1/memory/**} API surface — behind authentication.
 * Mirrors the equivalent {@code SecurityConfigTest} pattern used by
 * llm-service for the same actuator-scrape fix.
 */
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
                "/memory/health"
        );
    }

    @Test
    void publicEndpointsDoNotPermitMemoryApiPaths() {
        String[] endpoints = config.getPublicEndpoints();

        assertThat(endpoints).noneMatch(endpoint -> endpoint.startsWith("/api/v1/memory"));
    }
}
