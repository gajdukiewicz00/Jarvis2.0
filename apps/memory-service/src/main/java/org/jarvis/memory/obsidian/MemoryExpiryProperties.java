package org.jarvis.memory.obsidian;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Roadmap P1 #9 — TTL/expiry cleanup ({@code jarvis.memory.expiry.*}).
 *
 * @see MemoryExpiryCleanupService
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.memory.expiry")
public class MemoryExpiryProperties {

    /** Master switch for the scheduled expiry sweep. */
    private boolean enabled = true;

    /** Delay between sweeps, in milliseconds. Default: hourly. */
    private long cleanupIntervalMs = 3_600_000L;
}
