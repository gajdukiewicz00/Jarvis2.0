package org.jarvis.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 5 — payload published on {@code jarvis.commands.confirmation.request}
 * when a dangerous command is intercepted by the orchestrator.
 *
 * <p>The desktop-agent confirmation UI (or a voice handler) consumes this
 * envelope, presents it to the owner, and returns a
 * {@link ConfirmationResult} on
 * {@code jarvis.commands.confirmation.result}.</p>
 *
 * <p>The {@code commandId} is the same as the original {@link CommandEnvelope}
 * so the orchestrator can correlate the eventual decision back to the
 * pending command.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfirmationRequest {

    private String commandId;
    private String correlationId;
    private String userId;
    private String intent;
    private RiskLevel riskLevel;
    private DangerousAction dangerousAction;
    private CommandSource source;

    /** Free-form payload, mirrors {@link CommandEnvelope#getPayload()}. */
    private Map<String, Object> payload;

    /**
     * Short, human-friendly text used by UIs and voice prompts. Example:
     * {@code "delete file /tmp/notes.md (HIGH risk)"}.
     */
    private String prompt;

    private Instant requestedAt;
    private Instant expiresAt;

    /** Audit event id for the {@code confirmation.requested} record (Phase 8). */
    private String auditEventId;
}
