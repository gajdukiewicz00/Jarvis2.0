package org.jarvis.common.eventbus;

import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventCategory;
import org.jarvis.events.EventSeverity;
import org.jarvis.events.EventTopics;
import org.jarvis.events.JarvisEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPublisherTest {

    @Test
    void auditBuildsMinimalEventAndPublishesToAuditTopicWithCommandIdAsKey() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        publisher.audit(AuditEventType.COMMAND_EXECUTED, "trace-1", "agent-1", "user-1", "cmd-1",
                Map.of("k", "v"));

        assertEquals(1, template.sent.size());
        RecordingKafkaTemplate.Sent sent = template.sent.get(0);
        assertEquals(EventTopics.AUDIT, sent.topic());
        assertEquals("cmd-1", sent.key());
        assertTrue(sent.data().contains("\"eventType\":\"COMMAND_EXECUTED\""));
        assertTrue(sent.data().contains("\"source\":\"test-service\""));
        assertTrue(sent.data().contains("\"severity\":\"INFO\""));
        assertTrue(sent.data().contains("\"traceId\":\"trace-1\""));
    }

    @Test
    void severityForWarnEventTypesIsWarnAndOthersDefaultToInfo() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        publisher.audit(AuditEventType.COMMAND_FAILED, null, null, null, null, null);
        publisher.audit(AuditEventType.CONFIRMATION_DENIED, null, null, null, null, null);
        publisher.audit(AuditEventType.KILL_SWITCH_ENGAGED, null, null, null, null, null);
        publisher.audit(AuditEventType.MEMORY_WRITTEN, null, null, null, null, null);

        assertTrue(template.sent.get(0).data().contains("\"severity\":\"WARN\""));
        assertTrue(template.sent.get(1).data().contains("\"severity\":\"WARN\""));
        assertTrue(template.sent.get(2).data().contains("\"severity\":\"WARN\""));
        assertTrue(template.sent.get(3).data().contains("\"severity\":\"INFO\""));
    }

    @Test
    void publishKeysByCommandIdWhenPresent() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        publisher.publish(JarvisEvent.builder()
                .eventType(AuditEventType.COMMAND_QUEUED)
                .agentId("agent-1")
                .commandId("cmd-42")
                .build());

        assertEquals("cmd-42", template.sent.get(0).key());
    }

    @Test
    void publishKeysByAgentIdWhenCommandIdAbsent() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        publisher.publish(JarvisEvent.builder()
                .eventType(AuditEventType.COMMAND_QUEUED)
                .agentId("agent-99")
                .build());

        assertEquals("agent-99", template.sent.get(0).key());
    }

    @Test
    void publishKeysByGeneratedEventIdWhenCommandAndAgentIdAbsent() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        publisher.publish(JarvisEvent.builder()
                .eventType(AuditEventType.COMMAND_QUEUED)
                .eventId("evt-fixed-id")
                .build());

        assertEquals("evt-fixed-id", template.sent.get(0).key());
    }

    @Test
    void publishFillsInMissingEventIdCategorySourceAndOccurredAt() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "default-source");

        JarvisEvent event = JarvisEvent.builder().eventType(AuditEventType.COMMAND_QUEUED).build();
        publisher.publish(event);

        assertNotNull(event.getEventId());
        assertEquals(EventCategory.AUDIT, event.getCategory());
        assertEquals("default-source", event.getSource());
        assertNotNull(event.getOccurredAt());
    }

    @Test
    void publishRoutesEachCategoryToItsOwnTopic() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        publisher.publish(JarvisEvent.builder().eventType(AuditEventType.VOICE_SESSION_STARTED)
                .category(EventCategory.VOICE).build());
        publisher.publish(JarvisEvent.builder().eventType(AuditEventType.MEMORY_WRITTEN)
                .category(EventCategory.MEMORY).build());

        assertEquals(EventTopics.VOICE, template.sent.get(0).topic());
        assertEquals(EventTopics.MEMORY, template.sent.get(1).topic());
    }

    @Test
    void publishOfNullEventIsANoOp() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        assertDoesNotThrow(() -> publisher.publish(null));

        assertTrue(template.sent.isEmpty());
    }

    @Test
    void publishSwallowsRuntimeExceptionsFromKafkaTemplate() {
        RecordingKafkaTemplate template = new RecordingKafkaTemplate();
        template.throwOnSend = new RuntimeException("broker unavailable");
        AuditPublisher publisher = new AuditPublisher(template, "test-service");

        assertDoesNotThrow(() -> publisher.audit(AuditEventType.COMMAND_QUEUED, "t", "a", "u", "c", null));
    }

    private static final class RecordingKafkaTemplate extends KafkaTemplate<String, String> {

        private final List<Sent> sent = new ArrayList<>();
        private RuntimeException throwOnSend;

        RecordingKafkaTemplate() {
            super(() -> null);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            if (throwOnSend != null) {
                throw throwOnSend;
            }
            sent.add(new Sent(topic, key, data));
            return CompletableFuture.completedFuture(null);
        }

        private record Sent(String topic, String key, String data) {
        }
    }
}
