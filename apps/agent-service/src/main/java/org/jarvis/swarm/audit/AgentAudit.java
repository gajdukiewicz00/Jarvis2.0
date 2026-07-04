package org.jarvis.swarm.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Structured audit trail for swarm decisions. Emits one line per security-relevant
 * decision (permission allow/deny, panic block, task lifecycle) carrying the task id,
 * correlation id, role, and decision — but NEVER command output, file contents, or
 * secrets. Integrates with the platform Kafka audit bus when present (future); for now
 * a dedicated logger keeps the service dependency-light and the trail reconstructable.
 */
@Slf4j
@Component
public class AgentAudit {

    public void permissionAllowed(String taskId, String correlationId, String role, String permission) {
        log.info("AGENT_AUDIT decision=ALLOW perm={} role={} task={} corr={}",
                permission, role, taskId, correlationId);
    }

    public void permissionDenied(String taskId, String correlationId, String role, String permission, String reason) {
        log.warn("AGENT_AUDIT decision=DENY perm={} role={} task={} corr={} reason={}",
                permission, role, taskId, correlationId, reason);
    }

    public void panicBlocked(String taskId, String correlationId, String role) {
        log.warn("AGENT_AUDIT decision=PANIC_BLOCK role={} task={} corr={}", role, taskId, correlationId);
    }

    public void lifecycle(String taskId, String correlationId, String role, String event) {
        log.info("AGENT_AUDIT event={} role={} task={} corr={}", event, role, taskId, correlationId);
    }
}
