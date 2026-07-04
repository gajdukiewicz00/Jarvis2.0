package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeDeviceCatalogTest {

    private SmartHomeDeviceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new SmartHomeDeviceCatalog();
    }

    @Test
    void allReturnsBuiltInDevices() {
        assertEquals(4, catalog.all().size());
    }

    @Test
    void findByIdReturnsKnownDevice() {
        Optional<SmartHomeDeviceDefinition> device = catalog.findById("kitchen_light");

        assertTrue(device.isPresent());
        assertEquals("Kitchen Light", device.get().displayName());
    }

    @Test
    void findByIdReturnsEmptyForUnknownDevice() {
        assertTrue(catalog.findById("missing").isEmpty());
    }

    @Test
    void registerAddsNewDevice() {
        SmartHomeDeviceDefinition newDevice = new SmartHomeDeviceDefinition(
                "garage_door", "Garage Door", "Garage", SmartHomeDeviceType.LOCK,
                List.of("LOCK", "UNLOCK"), new LinkedHashMap<>(Map.of("locked", true)));

        SmartHomeDeviceDefinition registered = catalog.register(newDevice);

        assertEquals(newDevice, registered);
        assertEquals(5, catalog.all().size());
        assertTrue(catalog.findById("garage_door").isPresent());
    }

    @Test
    void registerReplacesExistingDeviceWithSameId() {
        SmartHomeDeviceDefinition replacement = new SmartHomeDeviceDefinition(
                "kitchen_light", "Kitchen Light v2", "Kitchen", SmartHomeDeviceType.LIGHT,
                List.of("TOGGLE"), new LinkedHashMap<>(Map.of("power", false)));

        catalog.register(replacement);

        assertEquals(4, catalog.all().size());
        assertEquals("Kitchen Light v2", catalog.findById("kitchen_light").get().displayName());
    }

    @Test
    void removeDeletesExistingDeviceAndReturnsTrue() {
        assertTrue(catalog.remove("desk_lamp"));
        assertFalse(catalog.findById("desk_lamp").isPresent());
        assertEquals(3, catalog.all().size());
    }

    @Test
    void removeReturnsFalseForUnknownDevice() {
        assertFalse(catalog.remove("missing"));
    }

    @Test
    void supportedActionsReturnsSortedDistinctActions() {
        List<String> expected = List.of(
                "BRIGHTEN", "DIM", "LOCK", "SET_BRIGHTNESS", "SET_COLOR",
                "SET_TEMPERATURE", "TOGGLE", "TURN_OFF", "TURN_ON", "UNLOCK");

        assertEquals(expected, catalog.supportedActions());
    }
}
