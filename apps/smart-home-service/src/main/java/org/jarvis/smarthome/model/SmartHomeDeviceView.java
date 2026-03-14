package org.jarvis.smarthome.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SmartHomeDeviceView(
        String id,
        String displayName,
        String room,
        SmartHomeDeviceType type,
        List<String> supportedActions,
        Map<String, Object> state,
        String provider,
        Instant updatedAt) {
}
