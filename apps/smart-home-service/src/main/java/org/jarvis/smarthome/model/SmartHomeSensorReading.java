package org.jarvis.smarthome.model;

import java.time.Instant;

/**
 * A single reading reported by a sensor device (e.g. temperature=21.5C,
 * motion=1). {@code metric} is normalized to upper case by
 * {@code SmartHomeSensorService} so lookups are case-insensitive.
 */
public record SmartHomeSensorReading(
        String deviceId,
        String metric,
        double value,
        String unit,
        Instant recordedAt) {
}
