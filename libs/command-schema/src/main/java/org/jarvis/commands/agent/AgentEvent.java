package org.jarvis.commands.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6 — single entry of the agent's live feed.
 *
 * <p>The 8 event types in {@link Type} match SPEC-1 § Phase 6's required
 * live-feed surface. Phase 8 will project events to Kafka
 * ({@code jarvis.desktop.activity.events}) and Postgres for history;
 * Pass 1 keeps them in an in-memory ring buffer surfaced over a future
 * UI panel.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEvent {

    public enum Type {
        VOICE_SESSION_STARTED,
        INTENT_CLASSIFIED,
        COMMAND_QUEUED,
        CONFIRMATION_REQUESTED,
        COMMAND_EXECUTED,
        MEMORY_WRITTEN,
        CV_EVENT_RECEIVED,
        ERROR,
        DEGRADED_STATE,
        KILL_SWITCH_ENGAGED,
        KILL_SWITCH_DISENGAGED
    }

    public enum Severity { INFO, WARN, ERROR }

    private String eventId;
    private String agentId;
    private Type type;
    private Severity severity;
    private String message;
    private Map<String, Object> payload;
    private Instant occurredAt;

    public static AgentEvent info(String agentId, Type type, String message,
                                  Map<String, Object> payload) {
        return AgentEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .agentId(agentId)
                .type(type)
                .severity(Severity.INFO)
                .message(message)
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
    }

    public static AgentEvent warn(String agentId, Type type, String message,
                                  Map<String, Object> payload) {
        return AgentEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .agentId(agentId)
                .type(type)
                .severity(Severity.WARN)
                .message(message)
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
    }

    public static AgentEvent error(String agentId, Type type, String message,
                                   Map<String, Object> payload) {
        return AgentEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .agentId(agentId)
                .type(type)
                .severity(Severity.ERROR)
                .message(message)
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
    }
}
