package org.jarvis.smarthome.model;

import java.util.List;

/**
 * A named, arbitrary collection of device ids that can be acted on together
 * (e.g. "downstairs_lights" → kitchen_light, desk_lamp). Unlike a
 * {@link SmartHomeRoom}, a device may belong to any number of groups at once.
 * In-memory, runtime-defined.
 */
public record SmartHomeGroup(String id, String name, List<String> deviceIds) {

    public SmartHomeGroup {
        deviceIds = deviceIds == null ? List.of() : deviceIds.stream().distinct().toList();
    }
}
