package org.jarvis.nlp.service.impl;

import org.jarvis.nlp.model.NlpResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedNlpServiceTest {

    private RuleBasedNlpService service;

    @BeforeEach
    void setUp() {
        service = new RuleBasedNlpService();
    }

    @Test
    void inferParsesTimerWithRussianNumberWordsAndSeconds() {
        NlpResult result = service.infer("Пожалуйста, поставь таймер на двадцать секунд", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("20", result.slots().get("amount"));
        assertEquals("sec", result.slots().get("unit"));
    }

    @Test
    void inferRecognizesCancelBargeInPhrases() {
        assertEquals("cancel", service.infer("отмена", "ru").intent());
        assertEquals("cancel", service.infer("стоп", "ru").intent());
        assertEquals("cancel", service.infer("заткнись", "ru").intent());
        assertEquals("cancel", service.infer("Jarvis stop", "en").intent());
        assertEquals("cancel", service.infer("cancel", "en").intent());
        // "пауза" still means media pause, not cancel
        assertEquals("pause", service.infer("пауза", "ru").intent());
    }

    @Test
    void inferRecognizesEnergyIntents() {
        assertEquals("set_energy", service.infer("я устал", "ru").intent());
        assertEquals("set_energy", service.infer("я выжат", "ru").intent());
        assertEquals("plan_by_energy", service.infer("спланируй день по энергии", "ru").intent());
        assertEquals("what_now", service.infer("что мне делать сейчас", "ru").intent());
    }

    @Test
    void inferTreatsShortTimerCommandAsMinutes() {
        NlpResult result = service.infer("таймер 15", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("15", result.slots().get("amount"));
        assertEquals("min", result.slots().get("unit"));
    }

    @Test
    void inferDefaultsVolumeDeltaToTenWhenAmountIsMissing() {
        NlpResult result = service.infer("сделай громче", "ru");

        assertEquals("volume_up", result.intent());
        assertEquals("10", result.slots().get("amount"));
        assertEquals("+", result.slots().get("direction"));
    }

    @Test
    void inferMatchesLiteralEnglishVolumeDownPhrase() {
        NlpResult result = service.infer("volume down", "en");

        assertEquals("volume_down", result.intent());
        assertEquals("10", result.slots().get("amount"));
        assertEquals("-", result.slots().get("direction"));
    }

    @Test
    void inferMatchesMakeItQuieterPhrase() {
        NlpResult result = service.infer("make it quieter", "en");

        assertEquals("volume_down", result.intent());
        assertEquals("10", result.slots().get("amount"));
        assertEquals("-", result.slots().get("direction"));
    }

    @Test
    void inferParsesKitchenLightTurnOnCommand() {
        NlpResult result = service.infer("включи кухонный свет", "ru");

        assertEquals("smart_home_action", result.intent());
        assertEquals("kitchen_light", result.slots().get("deviceId"));
        assertEquals("TURN_ON", result.slots().get("action"));
    }

    @Test
    void inferParsesThermostatTemperatureCommand() {
        NlpResult result = service.infer("set hall thermostat to 23", "en");

        assertEquals("smart_home_action", result.intent());
        assertEquals("hall_thermostat", result.slots().get("deviceId"));
        assertEquals("SET_TEMPERATURE", result.slots().get("action"));
        assertEquals("23", result.slots().get("payload"));
    }

    @Test
    void inferFallsBackForUnknownText() {
        NlpResult result = service.infer("расскажи анекдот про базу данных", "ru");

        assertEquals("fallback", result.intent());
        assertTrue(result.slots().isEmpty());
    }

    @Test
    void inferRecognisesCreateNoteForToday() {
        NlpResult result = service.infer("сделай заметку на сегодня", "ru");

        assertEquals("create_note", result.intent());
        assertEquals("today", result.slots().get("scope"));
    }

    @Test
    void inferRecognisesCreateNoteEnglish() {
        NlpResult result = service.infer("create a note about meeting agenda", "en");

        assertEquals("create_note", result.intent());
        assertEquals("meeting agenda", result.slots().get("topic"));
    }

    @Test
    void inferRecognisesGenericCreateNote() {
        NlpResult result = service.infer("запиши заметку", "ru");
        assertEquals("create_note", result.intent());
    }
}
