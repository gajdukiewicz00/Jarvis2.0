package org.jarvis.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JarvisEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void auditFactoryFillsRequiredFields() {
        JarvisEvent e = JarvisEvent.audit(AuditEventType.KILL_SWITCH_ENGAGED, "api-gateway");
        assertThat(e.getEventId()).startsWith("evt-");
        assertThat(e.getCategory()).isEqualTo(EventCategory.AUDIT);
        assertThat(e.getSeverity()).isEqualTo(EventSeverity.INFO);
        assertThat(e.getEventType()).isEqualTo(AuditEventType.KILL_SWITCH_ENGAGED);
        assertThat(e.getSource()).isEqualTo("api-gateway");
        assertThat(e.getOccurredAt()).isNotNull();
    }

    @Test
    void roundtripsThroughJackson() throws Exception {
        JarvisEvent original = JarvisEvent.builder()
                .eventId("evt-1")
                .eventType(AuditEventType.COMMAND_EXECUTED)
                .category(EventCategory.AUDIT)
                .severity(EventSeverity.WARN)
                .source("orchestrator")
                .traceId("trace-1")
                .agentId("agent-1")
                .userId("owner")
                .sessionId("sess-1")
                .commandId("cmd-1")
                .occurredAt(Instant.parse("2026-05-01T10:00:00Z"))
                .payload(Map.of("intent", "fs.delete-file", "exitCode", 0))
                .build();

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"eventType\":\"COMMAND_EXECUTED\"");
        assertThat(json).contains("\"category\":\"AUDIT\"");
        assertThat(json).contains("\"severity\":\"WARN\"");
        assertThat(json).contains("\"source\":\"orchestrator\"");

        JarvisEvent back = mapper.readValue(json, JarvisEvent.class);
        assertThat(back.getEventId()).isEqualTo("evt-1");
        assertThat(back.getEventType()).isEqualTo(AuditEventType.COMMAND_EXECUTED);
        assertThat(back.getCategory()).isEqualTo(EventCategory.AUDIT);
        assertThat(back.getSeverity()).isEqualTo(EventSeverity.WARN);
        assertThat(back.getCommandId()).isEqualTo("cmd-1");
        assertThat(back.getPayload()).containsEntry("intent", "fs.delete-file");
    }

    @Test
    void topicsCoverAllCategories() {
        for (EventCategory c : EventCategory.values()) {
            assertThat(EventTopics.forCategory(c)).isNotBlank();
        }
        assertThat(EventTopics.all()).hasSize(7);
    }

    @Test
    void unknownFieldsDoNotBreakDeserialization() throws Exception {
        String json = """
                {
                  "eventId":"evt-x",
                  "eventType":"COMMAND_QUEUED",
                  "category":"AUDIT",
                  "severity":"INFO",
                  "source":"orchestrator",
                  "occurredAt":"2026-05-01T10:00:00Z",
                  "newFutureField":"ignored-by-old-consumers",
                  "payload":{"k":"v"}
                }
                """;
        ObjectMapper lenient = mapper.copy()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JarvisEvent e = lenient.readValue(json, JarvisEvent.class);
        assertThat(e.getEventType()).isEqualTo(AuditEventType.COMMAND_QUEUED);
        assertThat(e.getPayload()).containsEntry("k", "v");
    }
}
