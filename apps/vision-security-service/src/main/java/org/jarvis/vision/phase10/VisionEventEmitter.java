package org.jarvis.vision.phase10;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventCategory;
import org.jarvis.events.EventSeverity;
import org.jarvis.events.JarvisEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 10 — emits {@link JarvisEvent}s for vision activity onto
 * {@code jarvis.vision.events}.
 *
 * <p>Wraps {@link AuditPublisher} so existing CV code keeps zero
 * dependency on Kafka. Truly privileged actions (face enrollment,
 * incident detection, demo-mode block) ALSO emit a parallel audit
 * event onto {@code jarvis.audit.events} via the standard publisher
 * — Phase 8's projector then captures them in the {@code audit_events}
 * table.</p>
 *
 * <p>All methods are fire-and-forget; failures degrade to a WARN log.</p>
 */
@Slf4j
@Component
public class VisionEventEmitter {

    private final ObjectProvider<AuditPublisher> auditProvider;

    public VisionEventEmitter(ObjectProvider<AuditPublisher> auditProvider) {
        this.auditProvider = auditProvider;
    }

    public void frameCaptured(String agentId, String userId, String captureType,
                              Map<String, Object> extra) {
        emit(AuditEventType.VISION_FRAME_CAPTURED, EventSeverity.INFO,
                agentId, userId, payload("captureType", captureType, extra),
                /* alsoAudit */ false);
    }

    public void faceEnrolled(String userId, String personId, int templateBytes) {
        emit(AuditEventType.VISION_FACE_ENROLLED, EventSeverity.WARN,
                null, userId,
                payload(Map.of("personId", personId, "templateBytes", templateBytes)),
                /* alsoAudit */ true);
    }

    public void faceRecognized(String userId, String personId, double confidence) {
        emit(AuditEventType.VISION_FACE_RECOGNIZED, EventSeverity.INFO,
                null, userId,
                payload(Map.of("personId", personId, "confidence", confidence)),
                /* alsoAudit */ false);
    }

    public void ocrPerformed(String userId, int charactersRead, String language) {
        emit(AuditEventType.VISION_OCR_PERFORMED, EventSeverity.INFO,
                null, userId,
                payload(Map.of("characters", charactersRead,
                        "language", language == null ? "auto" : language)),
                /* alsoAudit */ false);
    }

    public void incidentDetected(String userId, String incidentId, String kind, String severity) {
        emit(AuditEventType.VISION_INCIDENT_DETECTED, mapSeverity(severity),
                null, userId,
                payload(Map.of("incidentId", incidentId, "kind", kind, "severity", severity)),
                /* alsoAudit */ true);
    }

    public void framesPurged(int filesDeleted, long bytesFreed, int retentionDays) {
        emit(AuditEventType.VISION_FRAMES_PURGED, EventSeverity.INFO, null, null,
                payload(Map.of("filesDeleted", filesDeleted,
                        "bytesFreed", bytesFreed,
                        "retentionDays", retentionDays)),
                /* alsoAudit */ true);
    }

    public void demoModeBlocked(String agentId, String userId, String endpoint) {
        emit(AuditEventType.VISION_DEMO_MODE_BLOCK, EventSeverity.WARN,
                agentId, userId, payload(Map.of("endpoint", endpoint)),
                /* alsoAudit */ true);
    }

    private void emit(AuditEventType type, EventSeverity severity,
                      String agentId, String userId,
                      Map<String, Object> payload, boolean alsoAudit) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) {
            log.debug("vision event {} dropped (no AuditPublisher bean)", type);
            return;
        }
        JarvisEvent visionEvent = JarvisEvent.builder()
                .eventId(JarvisEvent.newEventId())
                .eventType(type)
                .category(EventCategory.VISION)
                .severity(severity)
                .source("vision-security-service")
                .agentId(agentId)
                .userId(userId)
                .occurredAt(Instant.now())
                .payload(payload)
                .build();
        publisher.publish(visionEvent);

        if (alsoAudit) {
            JarvisEvent auditEvent = JarvisEvent.builder()
                    .eventId(JarvisEvent.newEventId())
                    .eventType(type)
                    .category(EventCategory.AUDIT)
                    .severity(severity)
                    .source("vision-security-service")
                    .agentId(agentId)
                    .userId(userId)
                    .occurredAt(Instant.now())
                    .payload(payload)
                    .build();
            publisher.publish(auditEvent);
        }
    }

    private Map<String, Object> payload(Map<String, Object> base) {
        return base == null ? new HashMap<>() : new HashMap<>(base);
    }

    private Map<String, Object> payload(String firstKey, Object firstValue,
                                        Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>();
        p.put(firstKey, firstValue);
        if (extra != null) p.putAll(extra);
        return p;
    }

    private EventSeverity mapSeverity(String severity) {
        if (severity == null) return EventSeverity.INFO;
        return switch (severity.toUpperCase()) {
            case "CRITICAL", "HIGH", "ERROR" -> EventSeverity.ERROR;
            case "MEDIUM", "WARN" -> EventSeverity.WARN;
            default -> EventSeverity.INFO;
        };
    }
}
