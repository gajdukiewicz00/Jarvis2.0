package org.jarvis.smarthome.service.impl;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MockSmartHomeTransportTest {

    private final MockSmartHomeTransport transport = new MockSmartHomeTransport();

    @Test
    void dispatchLogsWithoutThrowing() {
        SmartHomeDeviceDefinition device = new SmartHomeDeviceDefinition(
                "desk_lamp", "Desk Lamp", "Office", SmartHomeDeviceType.LIGHT,
                List.of("TOGGLE"), new LinkedHashMap<>(Map.of("power", true)));

        assertDoesNotThrow(() ->
                transport.dispatch("user-1", device, new SmartHomeActionRequest("TOGGLE", null)));
    }

    @Test
    void dispatchHandlesNullPayloadWithoutThrowing() {
        SmartHomeDeviceDefinition device = new SmartHomeDeviceDefinition(
                "front_door_lock", "Front Door", "Entrance", SmartHomeDeviceType.LOCK,
                List.of("LOCK"), new LinkedHashMap<>(Map.of("locked", true)));

        assertDoesNotThrow(() ->
                transport.dispatch("user-2", device, new SmartHomeActionRequest("LOCK", "extra-payload")));
    }

    @Test
    void providerNameIsMock() {
        assertEquals("mock", transport.providerName());
    }
}
