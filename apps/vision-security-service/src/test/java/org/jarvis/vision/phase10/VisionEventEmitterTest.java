package org.jarvis.vision.phase10;

import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventCategory;
import org.jarvis.events.EventSeverity;
import org.jarvis.events.JarvisEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisionEventEmitterTest {

    private AuditPublisher publisher;
    private VisionEventEmitter emitter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        publisher = mock(AuditPublisher.class);
        ObjectProvider<AuditPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        emitter = new VisionEventEmitter(provider);
    }

    @Test
    void frameCapturedGoesToVisionTopicOnly() {
        emitter.frameCaptured("agent-1", "owner", "webcam",
                java.util.Map.of("frameId", "frame-1"));

        ArgumentCaptor<JarvisEvent> ec = ArgumentCaptor.forClass(JarvisEvent.class);
        verify(publisher, times(1)).publish(ec.capture());
        JarvisEvent event = ec.getValue();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.VISION_FRAME_CAPTURED);
        assertThat(event.getCategory()).isEqualTo(EventCategory.VISION);
        assertThat(event.getSeverity()).isEqualTo(EventSeverity.INFO);
        assertThat(event.getSource()).isEqualTo("vision-security-service");
        assertThat(event.getAgentId()).isEqualTo("agent-1");
        assertThat(event.getUserId()).isEqualTo("owner");
        assertThat(event.getPayload()).containsEntry("captureType", "webcam");
    }

    @Test
    void faceEnrolledAlsoEmitsToAudit() {
        emitter.faceEnrolled("owner", "person-42", 12345);

        ArgumentCaptor<JarvisEvent> ec = ArgumentCaptor.forClass(JarvisEvent.class);
        verify(publisher, times(2)).publish(ec.capture());
        List<JarvisEvent> emitted = ec.getAllValues();

        // First the VISION-channel event, then the AUDIT mirror.
        assertThat(emitted).extracting(JarvisEvent::getCategory)
                .containsExactly(EventCategory.VISION, EventCategory.AUDIT);
        assertThat(emitted).allSatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo(AuditEventType.VISION_FACE_ENROLLED);
            assertThat(e.getSeverity()).isEqualTo(EventSeverity.WARN);
            assertThat(e.getPayload()).containsEntry("personId", "person-42");
            assertThat(e.getPayload()).containsEntry("templateBytes", 12345);
        });
    }

    @Test
    void incidentSeverityMapsCriticalToError() {
        emitter.incidentDetected("owner", "inc-9", "intrusion", "CRITICAL");
        ArgumentCaptor<JarvisEvent> ec = ArgumentCaptor.forClass(JarvisEvent.class);
        verify(publisher, times(2)).publish(ec.capture()); // VISION + AUDIT
        assertThat(ec.getAllValues()).allSatisfy(e -> {
            assertThat(e.getSeverity()).isEqualTo(EventSeverity.ERROR);
            assertThat(e.getEventType()).isEqualTo(AuditEventType.VISION_INCIDENT_DETECTED);
        });
    }

    @Test
    void demoModeBlockEmitsBothChannels() {
        emitter.demoModeBlocked("agent-1", "guest", "/api/v1/vision/frames");
        verify(publisher, times(2)).publish(org.mockito.ArgumentMatchers.any(JarvisEvent.class));
    }

    @Test
    void noPublisherIsNoOp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditPublisher> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        VisionEventEmitter e = new VisionEventEmitter(empty);
        e.frameCaptured("a", "u", "webcam", null);
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(JarvisEvent.class));
    }
}
