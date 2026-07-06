package org.jarvis.smarthome.model;

import java.time.Instant;

/**
 * {@code needsConfirmation} is {@code true} when the action targeted a
 * security-critical device type (see {@code org.jarvis.smarthome.security.SafetyPolicy})
 * without an explicit confirmation flag — in that case {@code success} is
 * {@code false}, {@code device} reflects the device's unchanged current state,
 * and the action was never actuated.
 */
public record SmartHomeActionResult(
        boolean success,
        String userId,
        String action,
        String message,
        SmartHomeDeviceView device,
        Instant timestamp,
        boolean needsConfirmation) {
}
