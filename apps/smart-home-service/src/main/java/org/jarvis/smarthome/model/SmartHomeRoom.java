package org.jarvis.smarthome.model;

import java.util.List;

/**
 * A physical room a device can be assigned to (e.g. "Kitchen" → kitchen_light).
 * Unlike a {@link SmartHomeGroup}, assignment is exclusive: assigning a device to
 * a room removes it from any other room it previously belonged to. In-memory,
 * runtime-defined.
 */
public record SmartHomeRoom(String id, String name, List<String> deviceIds) {

    public SmartHomeRoom {
        deviceIds = deviceIds == null ? List.of() : deviceIds.stream().distinct().toList();
    }
}
