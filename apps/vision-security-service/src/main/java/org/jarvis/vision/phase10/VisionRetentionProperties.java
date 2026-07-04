package org.jarvis.vision.phase10;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 10 — raw-frame retention policy.
 *
 * <p>The existing IncidentStore writes per-user incident JSON + frames into
 * {@code ~/.jarvis/data/vision-security/users/{userId}/incidents/}. This
 * scheduler walks that tree and deletes anything older than {@link #days}
 * (default 7 days). Disabled mode is the safe default for tests.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.vision.frame-retention")
public class VisionRetentionProperties {
    private boolean enabled = true;
    private int days = 7;
    private long sweepIntervalMs = 3_600_000L;
    private String root = System.getProperty("user.home") + "/.jarvis/data/vision-security/users";
}
