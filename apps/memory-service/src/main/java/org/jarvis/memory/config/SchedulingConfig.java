package org.jarvis.memory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Roadmap P1 #9 — enables {@code @Scheduled} methods, e.g.
 * {@link org.jarvis.memory.obsidian.MemoryExpiryCleanupService}'s periodic
 * TTL sweep.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
