package org.jarvis.memory.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventCategory;
import org.jarvis.events.EventSeverity;
import org.jarvis.events.JarvisEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventConsumerTest {

    private AuditEventRepository repository;
    private ObjectMapper objectMapper;
    private AuditEventConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        objectMapper = mock(ObjectMapper.class);
        consumer = new AuditEventConsumer(repository, objectMapper);
    }

    private JarvisEvent event(String eventId) {
        return JarvisEvent.builder()
                .eventId(eventId)
                .eventType(AuditEventType.MEMORY_WRITTEN)
                .category(EventCategory.AUDIT)
                .severity(EventSeverity.INFO)
                .source("memory-service")
                .traceId("trace-1")
                .agentId("agent-1")
                .userId("user-1")
                .sessionId("session-1")
                .commandId("cmd-1")
                .occurredAt(Instant.parse("2026-05-01T00:00:00Z"))
                .payload(Map.of("k", "v"))
                .build();
    }

    @Test
    void malformedJsonIsLoggedAndSwallowed() throws Exception {
        when(objectMapper.readValue("not json", JarvisEvent.class))
                .thenThrow(mock(JsonProcessingException.class));

        consumer.onAuditEvent("not json", "some-key");

        verify(repository, never()).save(any());
    }

    @Test
    void duplicateEventIsIgnored() throws Exception {
        JarvisEvent evt = event("evt-1");
        when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(JarvisEvent.class)))
                .thenReturn(evt);
        when(repository.existsById("evt-1")).thenReturn(true);

        consumer.onAuditEvent("{}", "k1");

        verify(repository, never()).save(any());
    }

    @Test
    void newEventIsPersistedWithAllFieldsMapped() throws Exception {
        JarvisEvent evt = event("evt-2");
        when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(JarvisEvent.class)))
                .thenReturn(evt);
        when(repository.existsById("evt-2")).thenReturn(false);

        consumer.onAuditEvent("{}", "k2");

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(captor.capture());
        AuditEventEntity saved = captor.getValue();

        assertThat(saved.getEventId()).isEqualTo("evt-2");
        assertThat(saved.getEventType()).isEqualTo("MEMORY_WRITTEN");
        assertThat(saved.getCategory()).isEqualTo("AUDIT");
        assertThat(saved.getSeverity()).isEqualTo("INFO");
        assertThat(saved.getSource()).isEqualTo("memory-service");
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getAgentId()).isEqualTo("agent-1");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getSessionId()).isEqualTo("session-1");
        assertThat(saved.getCommandId()).isEqualTo("cmd-1");
        assertThat(saved.getOccurredAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(saved.getReceivedAt()).isNotNull();
        assertThat(saved.getPayload()).containsEntry("k", "v");
    }

    @Test
    void missingEventIdIsGenerated() throws Exception {
        JarvisEvent evt = JarvisEvent.builder()
                .eventType(AuditEventType.MEMORY_WRITTEN)
                .source(null)
                .category(null)
                .severity(null)
                .occurredAt(null)
                .build();
        when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(JarvisEvent.class)))
                .thenReturn(evt);
        when(repository.existsById(anyString())).thenReturn(false);

        consumer.onAuditEvent("{}", null);

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(captor.capture());
        AuditEventEntity saved = captor.getValue();

        assertThat(saved.getEventId()).startsWith("evt-");
        assertThat(saved.getEventType()).isEqualTo("MEMORY_WRITTEN");
        assertThat(saved.getCategory()).isEqualTo("UNKNOWN");
        assertThat(saved.getSeverity()).isEqualTo("UNKNOWN");
        assertThat(saved.getSource()).isEqualTo("unknown");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void concurrentInsertRaceIsSwallowedAsDataIntegrityViolation() throws Exception {
        JarvisEvent evt = event("evt-3");
        when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(JarvisEvent.class)))
                .thenReturn(evt);
        when(repository.existsById("evt-3")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("duplicate key")).when(repository).save(any());

        // Must not throw — a race against another consumer instance is expected/safe.
        consumer.onAuditEvent("{}", "k3");

        verify(repository).save(any());
    }

    @Test
    void unexpectedPersistenceFailureIsLoggedAndSwallowed() throws Exception {
        JarvisEvent evt = event("evt-4");
        when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.eq(JarvisEvent.class)))
                .thenReturn(evt);
        when(repository.existsById("evt-4")).thenReturn(false);
        doThrow(new RuntimeException("db is down")).when(repository).save(any());

        // Must not throw — projector failures are logged, not propagated to Kafka.
        consumer.onAuditEvent("{}", "k4");

        verify(repository).save(any());
    }
}
