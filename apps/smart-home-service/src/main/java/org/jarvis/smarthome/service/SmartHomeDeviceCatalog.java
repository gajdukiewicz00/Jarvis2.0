package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SmartHomeDeviceCatalog {

    // Mutable so devices can be added/removed at runtime without a redeploy.
    private final List<SmartHomeDeviceDefinition> devices = new CopyOnWriteArrayList<>(List.of(
            new SmartHomeDeviceDefinition(
                    "kitchen_light",
                    "Kitchen Light",
                    "Kitchen",
                    SmartHomeDeviceType.LIGHT,
                    List.of("TURN_ON", "TURN_OFF", "TOGGLE", "DIM", "BRIGHTEN", "SET_COLOR", "SET_BRIGHTNESS"),
                    new LinkedHashMap<>(Map.of("power", false, "brightness", 65, "color", "warm_white"))),
            new SmartHomeDeviceDefinition(
                    "desk_lamp",
                    "Desk Lamp",
                    "Office",
                    SmartHomeDeviceType.LIGHT,
                    List.of("TURN_ON", "TURN_OFF", "TOGGLE", "DIM", "BRIGHTEN", "SET_COLOR", "SET_BRIGHTNESS"),
                    new LinkedHashMap<>(Map.of("power", true, "brightness", 45, "color", "neutral_white"))),
            new SmartHomeDeviceDefinition(
                    "hall_thermostat",
                    "Hall Thermostat",
                    "Hallway",
                    SmartHomeDeviceType.THERMOSTAT,
                    List.of("TURN_ON", "TURN_OFF", "SET_TEMPERATURE"),
                    new LinkedHashMap<>(Map.of("power", true, "targetTemperature", 21.0, "mode", "HEAT"))),
            new SmartHomeDeviceDefinition(
                    "front_door_lock",
                    "Front Door",
                    "Entrance",
                    SmartHomeDeviceType.LOCK,
                    List.of("LOCK", "UNLOCK"),
                    new LinkedHashMap<>(Map.of("locked", true)))));

    public List<SmartHomeDeviceDefinition> all() {
        return List.copyOf(devices);
    }

    /** Add or replace a device definition at runtime. */
    public SmartHomeDeviceDefinition register(SmartHomeDeviceDefinition definition) {
        devices.removeIf(d -> d.id().equals(definition.id()));
        devices.add(definition);
        return definition;
    }

    /** Remove a device by id. Returns true if a device was removed. */
    public boolean remove(String deviceId) {
        return devices.removeIf(d -> d.id().equals(deviceId));
    }

    public Optional<SmartHomeDeviceDefinition> findById(String deviceId) {
        return devices.stream()
                .filter(device -> device.id().equals(deviceId))
                .findFirst();
    }

    public List<String> supportedActions() {
        return devices.stream()
                .flatMap(device -> device.supportedActions().stream())
                .distinct()
                .sorted()
                .toList();
    }
}
