package org.jarvis.common.eventbus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventCategory;
import org.jarvis.events.EventSeverity;
import org.jarvis.events.EventTopics;
import org.jarvis.events.JarvisEvent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 8 — fire-and-forget publisher of {@link JarvisEvent}s onto the
 * audit (and other) Kafka topics.
 *
 * <p>Idempotent / safe to call from any thread. Failures are logged at
 * WARN but never thrown — audit must never block the action it is
 * recording. Critical privileged actions also log the same content at
 * INFO so operators can reconstruct history when Kafka is unavailable.</p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
public class AuditPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;
    private final String defaultSource;

    public AuditPublisher(KafkaTemplate<String, String> kafkaTemplate,
                          @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String appName) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.defaultSource = appName;
    }

    /** Convenience: build + publish a minimal audit event. */
    public void audit(AuditEventType type, String traceId, String agentId,
                      String userId, String commandId, Map<String, Object> payload) {
        publish(JarvisEvent.builder()
                .eventId(JarvisEvent.newEventId())
                .eventType(type)
                .category(EventCategory.AUDIT)
                .severity(severityFor(type))
                .source(defaultSource)
                .traceId(traceId)
                .agentId(agentId)
                .userId(userId)
                .commandId(commandId)
                .occurredAt(Instant.now())
                .payload(payload)
                .build());
    }

    /** Publish a fully-formed event to its category's topic. */
    public void publish(JarvisEvent event) {
        if (event == null) return;
        if (event.getEventId() == null) event.setEventId(JarvisEvent.newEventId());
        if (event.getCategory() == null) event.setCategory(EventCategory.AUDIT);
        if (event.getSource() == null) event.setSource(defaultSource);
        if (event.getOccurredAt() == null) event.setOccurredAt(Instant.now());

        String topic = EventTopics.forCategory(event.getCategory());
        String key = event.getCommandId() != null ? event.getCommandId()
                : (event.getAgentId() != null ? event.getAgentId() : event.getEventId());
        try {
            String json = mapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, json);
            log.info("AUDIT [{}] type={} source={} agent={} user={} cmd={} trace={}",
                    event.getEventId(), event.getEventType(), event.getSource(),
                    event.getAgentId(), event.getUserId(), event.getCommandId(),
                    event.getTraceId());
        } catch (JsonProcessingException ex) {
            log.warn("audit publish JSON failure for event {}: {}", event.getEventId(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("audit publish failed for event {} on topic {}: {}",
                    event.getEventId(), topic, ex.getMessage());
        }
    }

    private EventSeverity severityFor(AuditEventType type) {
        return switch (type) {
            case COMMAND_FAILED, COMMAND_EXPIRED, KILL_SWITCH_ENGAGED,
                 CONFIRMATION_DENIED, CONFIRMATION_TIMEOUT,
                 CONFIRMATION_BLOCKED_DEMO_MODE,
                 CONFIRMATION_BLOCKED_NON_OWNER,
                 AGENT_HEARTBEAT_LOST -> EventSeverity.WARN;
            default -> EventSeverity.INFO;
        };
    }
}
