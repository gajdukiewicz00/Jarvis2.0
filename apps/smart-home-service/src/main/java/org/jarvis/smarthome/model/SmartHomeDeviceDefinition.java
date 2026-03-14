package org.jarvis.smarthome.model;

import java.util.List;
import java.util.Map;

public record SmartHomeDeviceDefinition(
        String id,
        String displayName,
        String room,
        SmartHomeDeviceType type,
        List<String> supportedActions,
        Map<String, Object> defaultState) {
}
