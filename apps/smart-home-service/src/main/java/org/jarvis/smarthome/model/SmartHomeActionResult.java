package org.jarvis.smarthome.model;

import java.time.Instant;

public record SmartHomeActionResult(
        boolean success,
        String userId,
        String action,
        String message,
        SmartHomeDeviceView device,
        Instant timestamp) {
}
