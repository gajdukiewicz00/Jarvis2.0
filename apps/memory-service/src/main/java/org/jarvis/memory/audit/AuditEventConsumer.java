package org.jarvis.memory.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.events.EventTopics;
import org.jarvis.events.JarvisEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 8 — projects {@code jarvis.audit.events} into the {@code audit_events}
 * Postgres table.
 *
 * <p>Idempotent on {@code eventId} — a duplicate delivery is logged at DEBUG
 * and ignored. Malformed payloads are logged at WARN and acked so the
 * topic doesn't block; future Phase 12 hardening can route these to a
 * dead-letter topic.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventConsumer {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTopics.AUDIT, groupId = "${jarvis.audit.consumer-group:audit-projector}")
    public void onAuditEvent(@Payload String json,
                             @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        JarvisEvent event;
        try {
            event = objectMapper.readValue(json, JarvisEvent.class);
        } catch (Exception ex) {
            log.warn("audit projector got malformed event (key={}): {}", key, ex.getMessage());
            return;
        }
        if (event.getEventId() == null) {
            event.setEventId("evt-" + UUID.randomUUID());
        }
        if (repository.existsById(event.getEventId())) {
            log.debug("audit duplicate ignored: {}", event.getEventId());
            return;
        }

        AuditEventEntity entity = AuditEventEntity.builder()
                .eventId(event.getEventId())
                .eventType(safe(event.getEventType()))
                .category(safe(event.getCategory()))
                .severity(safe(event.getSeverity()))
                .source(event.getSource() == null ? "unknown" : event.getSource())
                .traceId(event.getTraceId())
                .agentId(event.getAgentId())
                .userId(event.getUserId())
                .sessionId(event.getSessionId())
                .commandId(event.getCommandId())
                .occurredAt(event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt())
                .receivedAt(Instant.now())
                .payload(event.getPayload())
                .build();

        try {
            repository.save(entity);
            log.info("AUDIT projected eventId={} type={} src={} agent={} cmd={}",
                    entity.getEventId(), entity.getEventType(), entity.getSource(),
                    entity.getAgentId(), entity.getCommandId());
        } catch (DataIntegrityViolationException ex) {
            // Race against another consumer; safe to ignore.
            log.debug("audit row already present (race): {}", entity.getEventId());
        } catch (RuntimeException ex) {
            log.error("audit projector failed to persist {}: {}", entity.getEventId(), ex.getMessage(), ex);
        }
    }

    private static String safe(Object enumOrString) {
        return enumOrString == null ? "UNKNOWN" : enumOrString.toString();
    }
}
