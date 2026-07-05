package org.jarvis.smarthome.model;

import java.time.Instant;

/**
 * A record of a single scene activation, kept in the in-memory scene history
 * so callers can audit when a scene ran and how many of its steps succeeded.
 */
public record SmartHomeSceneActivation(
        String sceneName,
        String userId,
        Instant timestamp,
        int stepCount,
        int successCount,
        int failureCount) {
}
