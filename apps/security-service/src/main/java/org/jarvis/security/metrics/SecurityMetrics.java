package org.jarvis.security.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for security-relevant activity (exposed via
 * /actuator/prometheus): token revocations, failed logins, and a general
 * OWNER-facing audit-event trail. Mirrors agent-service's SwarmMetrics: thin
 * tagged counters backed by a single injected registry.
 *
 * <p>{@link #tokenRevoked} backs the {@code security_token_revocations_total{scope,reason}}
 * panel documented in {@code observability/README.md}. {@link #loginFailure}
 * and {@link #auditEvent} are additive counters covering the other two
 * signals called out in the wave-2 instrumentation task.
 */
@Component
public class SecurityMetrics {

    private final MeterRegistry registry;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records a single token revocation, tagged by scope ({@code single}|{@code all}) and reason. */
    public void tokenRevoked(String scope, String reason) {
        tokenRevoked(scope, reason, 1);
    }

    /**
     * Records {@code count} token revocations from one action (e.g. an
     * OWNER revoking every session for a user). No-op when {@code count} is
     * not positive, so bulk actions that affected nothing don't add a
     * zero-weight data point.
     */
    public void tokenRevoked(String scope, String reason, int count) {
        if (count > 0) {
            registry.counter("security.token.revocations", "scope", scope, "reason", reason).increment(count);
        }
    }

    /** Records a failed login attempt, tagged by reason. */
    public void loginFailure(String reason) {
        registry.counter("security.login.failures", "reason", reason).increment();
    }

    /** Records a generic OWNER-facing audit event, tagged by a small fixed-cardinality type. */
    public void auditEvent(String type) {
        registry.counter("security.audit.events", "type", type).increment();
    }
}
