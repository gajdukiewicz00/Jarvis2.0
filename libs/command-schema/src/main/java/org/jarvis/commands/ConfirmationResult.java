package org.jarvis.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Phase 5 — payload published on {@code jarvis.commands.confirmation.result}
 * once a confirmation decision has been made.
 *
 * <p>{@code decidedBy} carries the subject id of the speaker / clicker for
 * audit. The orchestrator validates that {@code decidedBy} matches the
 * original command's {@code userId}; otherwise the decision is rejected
 * with {@link ConfirmationDecision#BLOCKED_NON_OWNER}.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfirmationResult {

    private String commandId;
    private String correlationId;
    private ConfirmationDecision decision;
    private String decidedBy;
    private Instant decidedAt;

    /** Channel that produced the decision: {@code desktop}, {@code voice}, {@code mobile}, ... */
    private String channel;

    /** Optional human-readable reason for DENIED / BLOCKED. */
    private String reason;

    /** Audit event id for the {@code confirmation.decided} record (Phase 8). */
    private String auditEventId;

    public static ConfirmationResult timeout(String commandId, String correlationId,
                                             String reason) {
        return ConfirmationResult.builder()
                .commandId(commandId)
                .correlationId(correlationId)
                .decision(ConfirmationDecision.TIMEOUT)
                .decidedAt(Instant.now())
                .channel("system")
                .reason(reason)
                .build();
    }

    public static ConfirmationResult demoModeBlock(String commandId, String correlationId) {
        return ConfirmationResult.builder()
                .commandId(commandId)
                .correlationId(correlationId)
                .decision(ConfirmationDecision.BLOCKED_DEMO_MODE)
                .decidedAt(Instant.now())
                .channel("system")
                .reason("demo mode active — privileged action blocked")
                .build();
    }

    public static ConfirmationResult nonOwnerBlock(String commandId, String correlationId,
                                                   String speaker, String expectedOwner) {
        return ConfirmationResult.builder()
                .commandId(commandId)
                .correlationId(correlationId)
                .decision(ConfirmationDecision.BLOCKED_NON_OWNER)
                .decidedAt(Instant.now())
                .channel("system")
                .reason("speaker '" + speaker + "' is not the registered owner '" + expectedOwner + "'")
                .build();
    }
}
