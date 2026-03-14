package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.security.ActionValidator;
import org.jarvis.smarthome.service.impl.StatefulSmartHomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulSmartHomeServiceTest {

    private StatefulSmartHomeService service;
    private RecordingTransport transport;

    @BeforeEach
    void setUp() {
        ActionValidator validator = new ActionValidator();
        ReflectionTestUtils.setField(validator, "allowedActions", List.of(
                "TURN_ON", "TURN_OFF", "TOGGLE", "DIM", "BRIGHTEN", "SET_COLOR",
                "SET_TEMPERATURE", "SET_BRIGHTNESS", "LOCK", "UNLOCK"));
        transport = new RecordingTransport();
        service = new StatefulSmartHomeService(
                validator,
                new SmartHomeDeviceCatalog(),
                transport,
                Clock.fixed(Instant.parse("2026-03-14T10:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void executeActionTogglesOnlyCurrentUsersDeviceState() {
        assertFalse((Boolean) service.getDevice("user-a", "kitchen_light").state().get("power"));
        assertFalse((Boolean) service.getDevice("user-b", "kitchen_light").state().get("power"));

        SmartHomeActionResult result = service.executeAction(
                "user-a",
                "kitchen_light",
                new SmartHomeActionRequest("TOGGLE", null));

        assertTrue((Boolean) result.device().state().get("power"));
        assertFalse((Boolean) service.getDevice("user-b", "kitchen_light").state().get("power"));
        assertEquals("user-a", transport.lastUserId);
        assertEquals("TOGGLE", transport.lastAction.action());
    }

    @Test
    void executeActionUpdatesThermostatTargetTemperature() {
        SmartHomeActionResult result = service.executeAction(
                "user-a",
                "hall_thermostat",
                new SmartHomeActionRequest("SET_TEMPERATURE", "23.5"));

        assertEquals(23.5, result.device().state().get("targetTemperature"));
        assertEquals("mock-test", result.device().provider());
    }

    @Test
    void executeActionRejectsUnsupportedDeviceAction() {
        SmartHomeValidationException exception = assertThrows(
                SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "front_door_lock", new SmartHomeActionRequest("TURN_ON", null)));

        assertTrue(exception.getMessage().contains("not supported"));
    }

    private static final class RecordingTransport implements SmartHomeCommandTransport {
        private String lastUserId;
        private SmartHomeActionRequest lastAction;

        @Override
        public void dispatch(String userId, SmartHomeDeviceDefinition device, SmartHomeActionRequest request) {
            this.lastUserId = userId;
            this.lastAction = request;
        }

        @Override
        public String providerName() {
            return "mock-test";
        }
    }
}
