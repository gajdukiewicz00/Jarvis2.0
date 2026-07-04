package org.jarvis.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseSecurityConfigTest {

    private static final class TestSecurityConfig extends BaseSecurityConfig {
    }

    private final TestSecurityConfig config = new TestSecurityConfig();

    @Test
    void defaultPublicEndpointsCoverActuatorHealthInfoAndPrometheus() {
        String[] endpoints = config.getPublicEndpoints();

        assertArrayEquals(new String[] {
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/info",
                "/actuator/prometheus"
        }, endpoints);
    }

    @Test
    void createGatewayAuthFilterReturnsAFreshInstanceEachTime() {
        GatewayAuthFilter first = config.createGatewayAuthFilter();
        GatewayAuthFilter second = config.createGatewayAuthFilter();

        assertTrue(first instanceof GatewayAuthFilter);
        assertNotSame(first, second);
    }

    @Test
    void configureAdditionalSecurityDefaultHookIsANoOp() {
        assertDoesNotThrow(() -> config.configureAdditionalSecurity(null));
    }
}
