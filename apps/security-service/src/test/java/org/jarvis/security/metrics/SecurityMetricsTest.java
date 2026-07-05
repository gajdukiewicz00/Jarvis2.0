package org.jarvis.security.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityMetricsTest {

    private SimpleMeterRegistry registry;
    private SecurityMetrics securityMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        securityMetrics = new SecurityMetrics(registry);
    }

    @Test
    void tokenRevokedSingleIncrementsCounterByOne() {
        securityMetrics.tokenRevoked("single", "USER_LOGOUT");
        securityMetrics.tokenRevoked("single", "USER_LOGOUT");

        assertEquals(2.0, registry.counter("security.token.revocations", "scope", "single", "reason", "USER_LOGOUT")
                .count());
    }

    @Test
    void tokenRevokedWithCountIncrementsByThatCount() {
        securityMetrics.tokenRevoked("all", "ADMIN_REVOKED_ALL", 5);

        assertEquals(5.0, registry.counter("security.token.revocations", "scope", "all", "reason", "ADMIN_REVOKED_ALL")
                .count());
    }

    @Test
    void tokenRevokedWithZeroCountDoesNotIncrement() {
        securityMetrics.tokenRevoked("all", "ADMIN_REVOKED_ALL", 0);

        assertEquals(0.0, registry.counter("security.token.revocations", "scope", "all", "reason", "ADMIN_REVOKED_ALL")
                .count());
    }

    @Test
    void loginFailureIncrementsCounterTaggedByReason() {
        securityMetrics.loginFailure("INVALID_CREDENTIALS");
        securityMetrics.loginFailure("ACCOUNT_DISABLED");
        securityMetrics.loginFailure("INVALID_CREDENTIALS");

        assertEquals(2.0, registry.counter("security.login.failures", "reason", "INVALID_CREDENTIALS").count());
        assertEquals(1.0, registry.counter("security.login.failures", "reason", "ACCOUNT_DISABLED").count());
    }

    @Test
    void auditEventIncrementsCounterTaggedByType() {
        securityMetrics.auditEvent("LOGIN_SUCCESS");

        assertEquals(1.0, registry.counter("security.audit.events", "type", "LOGIN_SUCCESS").count());
    }
}
