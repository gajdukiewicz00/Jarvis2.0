package org.jarvis.events;

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
 * Phase 8 — canonical event envelope for Jarvis Kafka topics.
 *
 * <p>Every producer (orchestrator, voice-gateway, desktop agent, ...) wraps
 * its emit in a {@code JarvisEvent}. The projector consumes
 * {@code jarvis.audit.events} and writes one row per event into the
 * {@code audit_events} Postgres table.</p>
 *
 * <p>Stable wire format. Add fields, never rename. Default to {@code null}
 * for unknowns on deserialization so old consumers can read new producer
 * messages.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JarvisEvent {

    /** Globally unique id used by the projector primary key. */
    private String eventId;

    private AuditEventType eventType;
    private EventCategory category;
    private EventSeverity severity;
    private String source;          // service that emitted, e.g. "orchestrator"

    private String traceId;         // correlation across services
    private String agentId;
    private String userId;
    private String sessionId;
    private String commandId;       // when relevant

    private Instant occurredAt;

    private Map<String, Object> payload;

    public static String newEventId() {
        return "evt-" + UUID.randomUUID();
    }

    public static JarvisEvent audit(AuditEventType type, String source) {
        return JarvisEvent.builder()
                .eventId(newEventId())
                .eventType(type)
                .category(EventCategory.AUDIT)
                .severity(EventSeverity.INFO)
                .source(source)
                .occurredAt(Instant.now())
                .build();
    }
}
