package org.jarvis.vision.phase10;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 10 — vision-side mirror of the orchestrator's
 * {@code jarvis.demo-mode} flag.
 *
 * <p>When enabled, sensitive vision endpoints (frame ingest, face
 * enrollment) refuse with a {@link org.jarvis.events.AuditEventType#VISION_DEMO_MODE_BLOCK}
 * audit row instead of executing. Read-only endpoints (status, recent
 * incidents listing) keep working so the desktop panel can still render.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.demo-mode")
public class DemoModeProperties {
    private boolean enabled = false;
    private String reason = "demo mode active — privileged vision actions blocked";
}
