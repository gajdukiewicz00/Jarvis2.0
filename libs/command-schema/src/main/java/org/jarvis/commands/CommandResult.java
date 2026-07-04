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
 * Phase 4 — payload published by the desktop-agent (or any executor) on
 * {@code jarvis.commands.agent.result} once a command has been processed.
 *
 * <p>Always carries the original {@code commandId} and {@code correlationId}
 * so the orchestrator can match it to a pending request. {@code status} is
 * one of {@link CommandStatus#SUCCESS}, {@link CommandStatus#FAILED},
 * {@link CommandStatus#EXPIRED}, or {@link CommandStatus#REJECTED}.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandResult {

    private String commandId;
    private String correlationId;
    private CommandStatus status;
    private Instant completedAt;
    private long durationMillis;

    /** Free-form result payload for the orchestrator (window id, exit code, etc.). */
    private Map<String, Object> output;

    /** Human-readable reason for FAILED / EXPIRED / REJECTED. */
    private String errorReason;

    /** Audit event id for the {@code command.completed} record (Phase 8). */
    private String auditEventId;

    public static CommandResult success(String commandId, String correlationId,
                                        Map<String, Object> output, long durationMillis) {
        return CommandResult.builder()
                .commandId(commandId)
                .correlationId(correlationId)
                .status(CommandStatus.SUCCESS)
                .completedAt(Instant.now())
                .durationMillis(durationMillis)
                .output(output)
                .build();
    }

    public static CommandResult failed(String commandId, String correlationId,
                                       String reason, long durationMillis) {
        return CommandResult.builder()
                .commandId(commandId)
                .correlationId(correlationId)
                .status(CommandStatus.FAILED)
                .completedAt(Instant.now())
                .durationMillis(durationMillis)
                .errorReason(reason)
                .build();
    }

    public static CommandResult expired(String commandId, String correlationId, String reason) {
        return CommandResult.builder()
                .commandId(commandId)
                .correlationId(correlationId)
                .status(CommandStatus.EXPIRED)
                .completedAt(Instant.now())
                .errorReason(reason)
                .build();
    }
}
