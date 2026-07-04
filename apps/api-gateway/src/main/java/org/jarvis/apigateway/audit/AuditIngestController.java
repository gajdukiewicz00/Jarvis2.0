package org.jarvis.apigateway.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.EventCategory;
import org.jarvis.events.JarvisEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 8 — REST ingest for desktop-agent live-feed events.
 *
 * <p>The agent runs on the host (outside the cluster) and does NOT have a
 * Kafka client in Pass 1. It posts each {@link JarvisEvent} here; the
 * gateway re-publishes to Kafka via the standard {@link AuditPublisher}.
 * Pass 2 can replace this with a direct Kafka publisher in the agent
 * once the host has cluster network reachability for {@code kafka:9092}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditIngestController {

    private final ObjectProvider<AuditPublisher> auditProvider;

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingest(@RequestBody JarvisEvent event) {
        if (event == null || event.getEventType() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (event.getCategory() == null) {
            event.setCategory(EventCategory.AUDIT);
        }
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) {
            log.warn("audit ingest received but no AuditPublisher bean — Kafka likely not configured");
            return ResponseEntity.accepted().build();
        }
        publisher.publish(event);
        return ResponseEntity.accepted().build();
    }
}
