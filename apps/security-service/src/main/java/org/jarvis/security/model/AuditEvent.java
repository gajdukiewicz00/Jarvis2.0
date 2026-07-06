package org.jarvis.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted, filterable security audit-event row. Written by
 * {@link org.jarvis.security.service.AuditService#recordEvent} alongside the
 * existing {@link org.jarvis.security.metrics.SecurityMetrics#auditEvent}
 * counter hook, so the OWNER-only audit viewer can page and filter by user,
 * event type, and time range instead of relying solely on Micrometer counters
 * or the revocation-only merge in {@link org.jarvis.security.dto.AuditEventView}.
 */
@Entity
@Table(name = "audit_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "reason", length = 128)
    private String reason;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
