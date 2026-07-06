package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.IntentMatchStatus;
import org.jarvis.smarthome.model.SmartHomeIntentResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SmartHomeIntentService} end to end against the real
 * {@link IntentParser} and the built-in {@link SmartHomeDeviceCatalog}
 * devices (kitchen_light, desk_lamp, hall_thermostat, front_door_lock).
 */
class SmartHomeIntentServiceTest {

    private SmartHomeIntentService intentService;

    @BeforeEach
    void setUp() {
        intentService = new SmartHomeIntentService(new IntentParser(), new SmartHomeDeviceCatalog());
    }

    @Test
    void resolvesUniqueDeviceForEnglishTurnOnCommand() {
        SmartHomeIntentResolution resolution = intentService.resolve("turn on the kitchen light");

        assertEquals(IntentMatchStatus.RESOLVED, resolution.status());
        assertEquals("TURN_ON", resolution.action());
        assertEquals("kitchen_light", resolution.device().id());
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void resolvesUniqueDeviceForRussianTurnOffCommand() {
        SmartHomeIntentResolution resolution = intentService.resolve("выключи лампу в офисе");

        assertEquals(IntentMatchStatus.RESOLVED, resolution.status());
        assertEquals("TURN_OFF", resolution.action());
        assertEquals("desk_lamp", resolution.device().id());
    }

    @Test
    void resolvesUniqueDeviceWithPayloadForTemperatureCommand() {
        SmartHomeIntentResolution resolution = intentService.resolve("set the hall thermostat to 22 degrees");

        assertEquals(IntentMatchStatus.RESOLVED, resolution.status());
        assertEquals("SET_TEMPERATURE", resolution.action());
        assertEquals("22", resolution.payload());
        assertEquals("hall_thermostat", resolution.device().id());
    }

    @Test
    void resolvesUniqueDeviceForLockCommand() {
        SmartHomeIntentResolution resolution = intentService.resolve("lock the front door");

        assertEquals(IntentMatchStatus.RESOLVED, resolution.status());
        assertEquals("LOCK", resolution.action());
        assertEquals("front_door_lock", resolution.device().id());
    }

    @Test
    void flagsUnsupportedActionOnAnOtherwiseResolvedDevice() {
        SmartHomeIntentResolution resolution = intentService.resolve("lock the kitchen light");

        assertEquals(IntentMatchStatus.RESOLVED, resolution.status());
        assertEquals("kitchen_light", resolution.device().id());
        assertTrue(resolution.message().contains("does not support action"));
    }

    @Test
    void returnsAmbiguousWhenMultipleDevicesMatchEqually() {
        SmartHomeIntentResolution resolution = intentService.resolve("turn on the light");

        assertEquals(IntentMatchStatus.AMBIGUOUS, resolution.status());
        assertNull(resolution.device());
        assertEquals(2, resolution.candidates().size());
        assertTrue(resolution.candidates().stream().anyMatch(d -> d.id().equals("kitchen_light")));
        assertTrue(resolution.candidates().stream().anyMatch(d -> d.id().equals("desk_lamp")));
    }

    @Test
    void returnsUnknownWhenNoDeviceMatchesTheQuery() {
        SmartHomeIntentResolution resolution = intentService.resolve("turn on the garage heater");

        assertEquals(IntentMatchStatus.UNKNOWN, resolution.status());
        assertNull(resolution.device());
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void returnsUnknownWhenActionCannotBeParsed() {
        SmartHomeIntentResolution resolution = intentService.resolve("what's the weather today");

        assertEquals(IntentMatchStatus.UNKNOWN, resolution.status());
        assertNull(resolution.action());
        assertNull(resolution.device());
    }

    @Test
    void passesThroughAmbiguousStatusFromTheParserWithoutResolvingADevice() {
        SmartHomeIntentResolution resolution = intentService.resolve("turn on and turn off the kitchen light");

        assertEquals(IntentMatchStatus.AMBIGUOUS, resolution.status());
        assertNull(resolution.action());
        assertNull(resolution.device());
    }

    @Test
    void returnsUnknownWhenActionIsRecognizedButNoDeviceReferenceIsGiven() {
        SmartHomeIntentResolution resolution = intentService.resolve("turn on");

        assertEquals(IntentMatchStatus.UNKNOWN, resolution.status());
        assertEquals("TURN_ON", resolution.action());
        assertNull(resolution.device());
    }

    @Test
    void treatsNullUtteranceAsUnknown() {
        SmartHomeIntentResolution resolution = intentService.resolve(null);

        assertEquals(IntentMatchStatus.UNKNOWN, resolution.status());
        assertEquals("", resolution.utterance());
    }
}
