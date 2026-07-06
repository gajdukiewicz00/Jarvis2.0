package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.security.ActionValidator;
import org.jarvis.smarthome.security.SafetyPolicy;
import org.jarvis.smarthome.service.impl.StatefulSmartHomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulSmartHomeServiceTest {

    private StatefulSmartHomeService service;
    private RecordingTransport transport;
    private SmartHomeDeviceCatalog catalog;

    @BeforeEach
    void setUp() {
        ActionValidator validator = new ActionValidator();
        ReflectionTestUtils.setField(validator, "allowedActions", List.of(
                "TURN_ON", "TURN_OFF", "TOGGLE", "DIM", "BRIGHTEN", "SET_COLOR",
                "SET_TEMPERATURE", "SET_BRIGHTNESS", "LOCK", "UNLOCK", "OPEN", "CLOSE"));
        transport = new RecordingTransport();
        catalog = new SmartHomeDeviceCatalog();
        service = new StatefulSmartHomeService(
                validator,
                new SafetyPolicy(),
                catalog,
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

    @Test
    void listDevicesReturnsAllCatalogDevicesForUser() {
        List<SmartHomeDeviceView> views = service.listDevices("user-a");

        assertEquals(catalog.all().size(), views.size());
        assertTrue(views.stream().anyMatch(v -> v.id().equals("kitchen_light")));
        assertTrue(views.stream().anyMatch(v -> v.id().equals("front_door_lock")));
    }

    @Test
    void listDevicesRejectsBlankUserId() {
        assertThrows(SmartHomeValidationException.class, () -> service.listDevices(" "));
    }

    @Test
    void listDevicesRejectsNullUserId() {
        assertThrows(SmartHomeValidationException.class, () -> service.listDevices(null));
    }

    @Test
    void getDeviceThrowsNotFoundForUnknownDevice() {
        assertThrows(SmartHomeDeviceNotFoundException.class,
                () -> service.getDevice("user-a", "missing_device"));
    }

    @Test
    void getDeviceRejectsBlankUserId() {
        assertThrows(SmartHomeValidationException.class, () -> service.getDevice(" ", "kitchen_light"));
    }

    @Test
    void getDeviceTrimsUserId() {
        SmartHomeDeviceView view = service.getDevice(" user-a ", "kitchen_light");
        assertNotNull(view);
        assertEquals("kitchen_light", view.id());
    }

    @Test
    void executeActionRejectsNullRequest() {
        SmartHomeValidationException exception = assertThrows(
                SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "kitchen_light", null));
        assertEquals("Action is required", exception.getMessage());
    }

    @Test
    void executeActionRejectsBlankAction() {
        assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest(" ", null)));
    }

    @Test
    void executeActionRejectsBlankUserId() {
        assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction(" ", "kitchen_light", new SmartHomeActionRequest("TOGGLE", null)));
    }

    @Test
    void executeActionThrowsNotFoundForUnknownDevice() {
        assertThrows(SmartHomeDeviceNotFoundException.class,
                () -> service.executeAction("user-a", "missing_device", new SmartHomeActionRequest("TOGGLE", null)));
    }

    @Test
    void executeActionRejectsActionNotOnAllowList() {
        assertThrows(ResponseStatusException.class,
                () -> service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest("DELETE_DEVICE", null)));
    }

    @Test
    void executeActionNormalizesHyphenatedLowercaseAction() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("set-brightness", "80"));

        assertEquals("SET_BRIGHTNESS", result.action());
        assertEquals(80, result.device().state().get("brightness"));
    }

    @Test
    void executeActionTurnsLightOnAndOff() {
        SmartHomeActionResult on = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("TURN_ON", null));
        assertTrue((Boolean) on.device().state().get("power"));

        SmartHomeActionResult off = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("TURN_OFF", null));
        assertFalse((Boolean) off.device().state().get("power"));
    }

    @Test
    void executeActionDimsLightDecreasingBrightness() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("DIM", null));

        assertEquals(55, result.device().state().get("brightness"));
        assertTrue((Boolean) result.device().state().get("power"));
    }

    @Test
    void executeActionDimBrightnessNeverGoesBelowZeroAndTurnsOff() {
        for (int i = 0; i < 10; i++) {
            service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest("DIM", null));
        }

        SmartHomeDeviceView view = service.getDevice("user-a", "kitchen_light");
        assertEquals(0, view.state().get("brightness"));
        assertFalse((Boolean) view.state().get("power"));
    }

    @Test
    void executeActionBrightensLightIncreasingBrightnessCappedAt100() {
        for (int i = 0; i < 5; i++) {
            service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest("BRIGHTEN", null));
        }

        SmartHomeDeviceView view = service.getDevice("user-a", "kitchen_light");
        assertEquals(100, view.state().get("brightness"));
        assertTrue((Boolean) view.state().get("power"));
    }

    @Test
    void executeActionSetBrightnessValidValue() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "80"));

        assertEquals(80, result.device().state().get("brightness"));
        assertTrue((Boolean) result.device().state().get("power"));
    }

    @Test
    void executeActionSetBrightnessZeroTurnsPowerOff() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "0"));

        assertEquals(0, result.device().state().get("brightness"));
        assertFalse((Boolean) result.device().state().get("power"));
    }

    @Test
    void executeActionSetBrightnessRejectsOutOfRangeValue() {
        SmartHomeValidationException exception = assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "150")));

        assertTrue(exception.getMessage().contains("must be between"));
    }

    @Test
    void executeActionSetBrightnessRejectsNonNumericPayload() {
        SmartHomeValidationException exception = assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "abc")));

        assertTrue(exception.getMessage().contains("must be numeric"));
    }

    @Test
    void executeActionSetColorUpdatesColorAndTurnsOn() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("SET_COLOR", "blue"));

        assertEquals("blue", result.device().state().get("color"));
        assertTrue((Boolean) result.device().state().get("power"));
    }

    @Test
    void executeActionSetColorRejectsBlankPayload() {
        SmartHomeValidationException exception = assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "kitchen_light", new SmartHomeActionRequest("SET_COLOR", " ")));

        assertEquals("Color payload is required", exception.getMessage());
    }

    @Test
    void executeActionSetsThermostatPowerOnAndOff() {
        SmartHomeActionResult on = service.executeAction(
                "user-a", "hall_thermostat", new SmartHomeActionRequest("TURN_ON", null));
        assertTrue((Boolean) on.device().state().get("power"));

        SmartHomeActionResult off = service.executeAction(
                "user-a", "hall_thermostat", new SmartHomeActionRequest("TURN_OFF", null));
        assertFalse((Boolean) off.device().state().get("power"));
    }

    @Test
    void executeActionSetTemperatureRejectsOutOfRangeValue() {
        SmartHomeValidationException exception = assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "hall_thermostat", new SmartHomeActionRequest("SET_TEMPERATURE", "40")));

        assertTrue(exception.getMessage().contains("must be between"));
    }

    @Test
    void executeActionSetTemperatureRejectsNonNumericPayload() {
        SmartHomeValidationException exception = assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "hall_thermostat", new SmartHomeActionRequest("SET_TEMPERATURE", "warm")));

        assertTrue(exception.getMessage().contains("must be numeric"));
    }

    @Test
    void executeActionLocksAndUnlocksDoorWhenConfirmed() {
        SmartHomeActionResult locked = service.executeAction(
                "user-a", "front_door_lock", new SmartHomeActionRequest("LOCK", null), true);
        assertTrue(locked.success());
        assertFalse(locked.needsConfirmation());
        assertTrue((Boolean) locked.device().state().get("locked"));

        SmartHomeActionResult unlocked = service.executeAction(
                "user-a", "front_door_lock", new SmartHomeActionRequest("UNLOCK", null), true);
        assertTrue(unlocked.success());
        assertFalse((Boolean) unlocked.device().state().get("locked"));
    }

    @Test
    void executeActionBlocksUnconfirmedLockAction() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "front_door_lock", new SmartHomeActionRequest("UNLOCK", null), false);

        assertFalse(result.success());
        assertTrue(result.needsConfirmation());
        assertTrue(result.message().toLowerCase().contains("confirmation"));
        // Device state is unchanged — the action was never applied.
        assertTrue((Boolean) result.device().state().get("locked"));
    }

    @Test
    void executeActionDefaultThreeArgOverloadTreatsActionAsUnconfirmed() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "front_door_lock", new SmartHomeActionRequest("UNLOCK", null));

        assertFalse(result.success());
        assertTrue(result.needsConfirmation());
    }

    @Test
    void executeActionBlocksUnconfirmedDoorAction() {
        catalog.register(new SmartHomeDeviceDefinition(
                "front_door", "Front Door", "Entrance", SmartHomeDeviceType.DOOR,
                List.of("OPEN", "CLOSE"), new LinkedHashMap<>(Map.of("open", false))));

        SmartHomeActionResult result = service.executeAction(
                "user-a", "front_door", new SmartHomeActionRequest("OPEN", null), false);

        assertFalse(result.success());
        assertTrue(result.needsConfirmation());
        assertFalse((Boolean) result.device().state().get("open"));
    }

    @Test
    void executeActionOpensDoorWhenConfirmed() {
        catalog.register(new SmartHomeDeviceDefinition(
                "front_door", "Front Door", "Entrance", SmartHomeDeviceType.DOOR,
                List.of("OPEN", "CLOSE"), new LinkedHashMap<>(Map.of("open", false))));

        SmartHomeActionResult result = service.executeAction(
                "user-a", "front_door", new SmartHomeActionRequest("OPEN", null), true);

        assertTrue(result.success());
        assertFalse(result.needsConfirmation());
        assertTrue((Boolean) result.device().state().get("open"));
    }

    @Test
    void executeActionBlocksUnconfirmedGarageAction() {
        catalog.register(new SmartHomeDeviceDefinition(
                "main_garage", "Main Garage", "Garage", SmartHomeDeviceType.GARAGE,
                List.of("OPEN", "CLOSE"), new LinkedHashMap<>(Map.of("open", false))));

        SmartHomeActionResult result = service.executeAction(
                "user-a", "main_garage", new SmartHomeActionRequest("OPEN", null), false);

        assertFalse(result.success());
        assertTrue(result.needsConfirmation());
    }

    @Test
    void executeActionDoesNotRequireConfirmationForNonCriticalDevice() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("TOGGLE", null), false);

        assertTrue(result.success());
        assertFalse(result.needsConfirmation());
    }

    @Test
    void executeActionReturnsLocalMessageWhenProviderIsMock() {
        StatefulSmartHomeService mockProviderService = new StatefulSmartHomeService(
                validatorWithDefaults(),
                new SafetyPolicy(),
                new SmartHomeDeviceCatalog(),
                new RecordingTransport("mock"),
                Clock.fixed(Instant.parse("2026-03-14T10:30:00Z"), ZoneOffset.UTC));

        SmartHomeActionResult result = mockProviderService.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("TOGGLE", null));

        assertEquals("Action executed locally", result.message());
    }

    @Test
    void executeActionReturnsMqttMessageWhenProviderIsNotMock() {
        SmartHomeActionResult result = service.executeAction(
                "user-a", "kitchen_light", new SmartHomeActionRequest("TOGGLE", null));

        assertEquals("Action dispatched via MQTT and local state updated", result.message());
    }

    @Test
    void supportedActionsDelegatesToCatalog() {
        assertEquals(catalog.supportedActions(), service.supportedActions());
    }

    @Test
    void executeActionTurnsSwitchOnAndOff() {
        registerSwitchDevice();

        SmartHomeActionResult on = service.executeAction(
                "user-a", "hall_switch", new SmartHomeActionRequest("TURN_ON", null));
        assertTrue((Boolean) on.device().state().get("power"));

        SmartHomeActionResult off = service.executeAction(
                "user-a", "hall_switch", new SmartHomeActionRequest("TURN_OFF", null));
        assertFalse((Boolean) off.device().state().get("power"));
    }

    @Test
    void executeActionTogglesSwitch() {
        registerSwitchDevice();

        SmartHomeActionResult result = service.executeAction(
                "user-a", "hall_switch", new SmartHomeActionRequest("TOGGLE", null));

        assertTrue((Boolean) result.device().state().get("power"));
    }

    @Test
    void executeActionRejectsUnsupportedSensorAction() {
        SmartHomeDeviceDefinition sensor = new SmartHomeDeviceDefinition(
                "hall_motion_sensor", "Hall Motion Sensor", "Hallway", SmartHomeDeviceType.MOTION_SENSOR,
                List.of("TURN_ON"), new LinkedHashMap<>(Map.of()));
        catalog.register(sensor);

        SmartHomeValidationException exception = assertThrows(SmartHomeValidationException.class,
                () -> service.executeAction("user-a", "hall_motion_sensor", new SmartHomeActionRequest("TURN_ON", null)));

        assertTrue(exception.getMessage().contains("read-only"));
    }

    private void registerSwitchDevice() {
        SmartHomeDeviceDefinition switchDevice = new SmartHomeDeviceDefinition(
                "hall_switch", "Hall Switch", "Hallway", SmartHomeDeviceType.SWITCH,
                List.of("TURN_ON", "TURN_OFF", "TOGGLE"), new LinkedHashMap<>(Map.of("power", false)));
        catalog.register(switchDevice);
    }

    private static ActionValidator validatorWithDefaults() {
        ActionValidator validator = new ActionValidator();
        ReflectionTestUtils.setField(validator, "allowedActions", List.of(
                "TURN_ON", "TURN_OFF", "TOGGLE", "DIM", "BRIGHTEN", "SET_COLOR",
                "SET_TEMPERATURE", "SET_BRIGHTNESS", "LOCK", "UNLOCK"));
        return validator;
    }

    private static final class RecordingTransport implements SmartHomeCommandTransport {
        private final String providerName;
        private String lastUserId;
        private SmartHomeActionRequest lastAction;

        private RecordingTransport() {
            this("mock-test");
        }

        private RecordingTransport(String providerName) {
            this.providerName = providerName;
        }

        @Override
        public void dispatch(String userId, SmartHomeDeviceDefinition device, SmartHomeActionRequest request) {
            this.lastUserId = userId;
            this.lastAction = request;
        }

        @Override
        public String providerName() {
            return providerName;
        }
    }
}
