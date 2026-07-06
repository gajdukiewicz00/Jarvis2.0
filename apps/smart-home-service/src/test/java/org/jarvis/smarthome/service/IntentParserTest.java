package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.IntentMatchStatus;
import org.jarvis.smarthome.model.ParsedIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentParserTest {

    private IntentParser parser;

    @BeforeEach
    void setUp() {
        parser = new IntentParser();
    }

    @Test
    void parsesEnglishTurnOnWithDeviceReference() {
        ParsedIntent result = parser.parse("turn on the kitchen light");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("TURN_ON", result.action());
        assertTrue(result.deviceQuery().contains("kitchen"));
        assertTrue(result.deviceQuery().contains("light"));
        assertNull(result.payload());
        assertEquals(0.9, result.confidence());
    }

    @Test
    void parsesEnglishTurnOffWithDeviceReference() {
        ParsedIntent result = parser.parse("turn off the desk lamp");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("TURN_OFF", result.action());
        assertEquals("desk lamp", result.deviceQuery());
    }

    @Test
    void parsesEnglishSetTemperatureWithNumericPayload() {
        ParsedIntent result = parser.parse("set living room to 22 degrees");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("SET_TEMPERATURE", result.action());
        assertEquals("22", result.payload());
        assertEquals("living room", result.deviceQuery());
    }

    @Test
    void parsesNumericPayloadAfterTriggerWhenAnUnrelatedNumberPrecedesTheTriggerWord() {
        ParsedIntent result = parser.parse("set thermostat 2 temperature to 22 degrees");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("SET_TEMPERATURE", result.action());
        assertEquals("22", result.payload());
    }

    @Test
    void parsesEnglishSetColorWithWordPayload() {
        ParsedIntent result = parser.parse("set the desk lamp color to blue");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("SET_COLOR", result.action());
        assertEquals("blue", result.payload());
        assertEquals("desk lamp", result.deviceQuery());
    }

    @Test
    void parsesEnglishLockCommand() {
        ParsedIntent result = parser.parse("lock the front door");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("LOCK", result.action());
        assertEquals("front door", result.deviceQuery());
    }

    @Test
    void parsesRussianTurnOnWithKitchenRoom() {
        ParsedIntent result = parser.parse("включи свет на кухне");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("TURN_ON", result.action());
        assertEquals("light kitchen", result.deviceQuery());
    }

    @Test
    void parsesRussianTurnOffWithOfficeRoom() {
        ParsedIntent result = parser.parse("выключи лампу в офисе");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("TURN_OFF", result.action());
        assertEquals("lamp office", result.deviceQuery());
    }

    @Test
    void parsesRussianSetTemperatureWithNumericPayload() {
        ParsedIntent result = parser.parse("поставь 22 градуса в гостиной");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("SET_TEMPERATURE", result.action());
        assertEquals("22", result.payload());
        assertEquals("living room", result.deviceQuery());
    }

    @Test
    void parsesRussianLockCommand() {
        ParsedIntent result = parser.parse("закрой входную дверь");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("LOCK", result.action());
        assertEquals("front door", result.deviceQuery());
    }

    @Test
    void flagsAmbiguousWhenBothTurnOnAndTurnOffAreMentioned() {
        ParsedIntent result = parser.parse("turn on and turn off the kitchen light");

        assertEquals(IntentMatchStatus.AMBIGUOUS, result.status());
        assertNull(result.action());
        assertEquals(0.3, result.confidence());
    }

    @Test
    void flagsAmbiguousForConflictingRussianCommands() {
        ParsedIntent result = parser.parse("включи и выключи свет");

        assertEquals(IntentMatchStatus.AMBIGUOUS, result.status());
        assertNull(result.action());
    }

    @Test
    void flagsUnknownForUnrecognizedEnglishUtterance() {
        ParsedIntent result = parser.parse("what's the weather today");

        assertEquals(IntentMatchStatus.UNKNOWN, result.status());
        assertNull(result.action());
        assertEquals(0.0, result.confidence());
    }

    @Test
    void flagsUnknownForUnrecognizedRussianUtterance() {
        ParsedIntent result = parser.parse("как дела сегодня");

        assertEquals(IntentMatchStatus.UNKNOWN, result.status());
        assertNull(result.action());
    }

    @Test
    void flagsUnknownForNullUtterance() {
        ParsedIntent result = parser.parse(null);

        assertEquals(IntentMatchStatus.UNKNOWN, result.status());
    }

    @Test
    void flagsUnknownForBlankUtterance() {
        ParsedIntent result = parser.parse("   ");

        assertEquals(IntentMatchStatus.UNKNOWN, result.status());
    }

    @Test
    void recognizesActionButFlagsMissingDeviceReference() {
        ParsedIntent result = parser.parse("turn on");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("TURN_ON", result.action());
        assertNull(result.deviceQuery());
        assertEquals(0.55, result.confidence());
    }

    @Test
    void recognizesActionButFlagsMissingRequiredPayload() {
        ParsedIntent result = parser.parse("set the desk lamp brightness");

        assertEquals(IntentMatchStatus.RESOLVED, result.status());
        assertEquals("SET_BRIGHTNESS", result.action());
        assertNull(result.payload());
        assertEquals("desk lamp", result.deviceQuery());
        assertEquals(0.7, result.confidence());
    }
}
