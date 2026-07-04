package org.jarvis.commands;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 4 — canonical envelope for every command flowing through RabbitMQ.
 *
 * <p>Stable wire format. Every field is required to satisfy SPEC-1 §
 * "Messaging Split" / Phase 4 task list. Don't break compatibility — add
 * fields, never rename them; fall back to defaults on deserialization.</p>
 *
 * <p>{@link #commandId} is the idempotency key. Consumers MUST deduplicate
 * by command_id and ignore duplicates within a configurable window.</p>
 *
 * <p>{@link #expiresAt} is the soft deadline. The publisher also sets the
 * RabbitMQ {@code expiration} property so the broker drops the message
 * automatically when the queue TTL fires; the consumer also checks
 * {@code now > expiresAt} on receipt and routes to DLQ.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandEnvelope {

    /** Globally unique command id. Used for idempotency + correlation. */
    private String commandId;

    /** End-to-end correlation id (voice session -> orchestrator -> agent). */
    private String correlationId;

    /** Subject id of the user issuing the command (security/auth context). */
    private String userId;

    /** Origin of the command. */
    private CommandSource source;

    /** Normalized intent name (e.g. {@code pc.window.focus}, {@code home.light.on}). */
    private String intent;

    /** Risk classification (Phase 5 confirmation gate). */
    private RiskLevel riskLevel;

    /** Whether the orchestrator already decided confirmation is needed. */
    private boolean requiresConfirmation;

    /** Wall-clock when the orchestrator created the command. */
    private Instant createdAt;

    /** Hard deadline. Consumers reject (status=EXPIRED) once {@code now > expiresAt}. */
    private Instant expiresAt;

    /** Lifecycle state (mutable as the message moves through stages). */
    private CommandStatus status;

    /** Audit event id for the {@code command.created} record (Phase 8). */
    private String auditEventId;

    /** Free-form payload for the executor (window name, file path, etc.). */
    private Map<String, Object> payload;

    /**
     * @return true if the command's hard deadline has already passed.
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * Mint a new command id when the publisher hasn't supplied one.
     */
    public static String newCommandId() {
        return "cmd-" + UUID.randomUUID();
    }

    /**
     * Equality is intent + payload + user — sufficient for testing only.
     * Idempotency in the pipeline uses {@link #commandId} alone.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CommandEnvelope other)) return false;
        return Objects.equals(commandId, other.commandId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(commandId);
    }
}
