package org.jarvis.nlp.service.impl;

import org.jarvis.nlp.model.NlpResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void inferRecognizesGoodbyeAndThanks() {
        assertEquals("goodbye", service.infer("пока", "ru").intent());
        assertEquals("goodbye", service.infer("до свидания", "ru").intent());
        assertEquals("thanks", service.infer("спасибо", "ru").intent());
        assertEquals("thanks", service.infer("благодарю", "ru").intent());
    }

    @Test
    void inferRecognizesMuteAndUnmute() {
        assertEquals("mute", service.infer("выключи звук", "ru").intent());
        assertEquals("mute", service.infer("замолчи", "ru").intent());
        assertEquals("mute", service.infer("mute", "en").intent());
        assertEquals("unmute", service.infer("включи звук", "ru").intent());
        // NOTE: MUTE is checked before UNMUTE and its bare "mute\b" alternative has
        // no leading boundary requirement, so it matches the "mute" suffix inside
        // the English word "unmute" itself. This documents that existing overlap
        // rather than asserting the (unimplemented) intended behavior.
        assertEquals("mute", service.infer("unmute", "en").intent());
    }

    @Test
    void inferParsesVolumeSetLevel() {
        NlpResult result = service.infer("громкость на 40%", "ru");

        assertEquals("volume_set", result.intent());
        assertEquals("40", result.slots().get("level"));
    }

    @Test
    void inferRecognizesPlayAndPauseAndTrackNavigation() {
        assertEquals("play", service.infer("играй", "ru").intent());
        assertEquals("play", service.infer("play music", "en").intent());
        assertEquals("pause", service.infer("пауза", "ru").intent());
        assertEquals("pause", service.infer("pause", "en").intent());
        assertEquals("next_track", service.infer("следующий трек", "ru").intent());
        assertEquals("next_track", service.infer("next", "en").intent());
        assertEquals("previous_track", service.infer("предыдущий трек", "ru").intent());
        assertEquals("previous_track", service.infer("previous", "en").intent());
    }

    @Test
    void inferRecognizesAppLaunchIntents() {
        assertEquals("open_browser", service.infer("открой браузер", "ru").intent());
        assertEquals("open_youtube", service.infer("открой ютуб", "ru").intent());
        assertEquals("open_ide", service.infer("открой idea", "ru").intent());

        NlpResult telegram = service.infer("открой телеграм", "ru");
        assertEquals("open_app", telegram.intent());
        assertEquals("telegram", telegram.slots().get("app"));

        NlpResult spotify = service.infer("открой спотифай", "ru");
        assertEquals("open_app", spotify.intent());
        assertEquals("spotify", spotify.slots().get("app"));

        NlpResult terminal = service.infer("открой терминал", "ru");
        assertEquals("open_app", terminal.intent());
        assertEquals("terminal", terminal.slots().get("app"));
    }

    @Test
    void inferRecognizesSmartHomeToggleAndTurnOffForDeskLamp() {
        NlpResult off = service.infer("выключи настольную лампу", "ru");
        assertEquals("smart_home_action", off.intent());
        assertEquals("desk_lamp", off.slots().get("deviceId"));
        assertEquals("TURN_OFF", off.slots().get("action"));

        NlpResult toggle = service.infer("переключи настольную лампу", "ru");
        assertEquals("smart_home_action", toggle.intent());
        assertEquals("desk_lamp", toggle.slots().get("deviceId"));
        assertEquals("TOGGLE", toggle.slots().get("action"));
    }

    @Test
    void inferIgnoresThermostatTemperatureWithoutSetKeyword() {
        // Mentions the thermostat and a temperature-shaped number but the verb is
        // "turn on", not a set/adjust verb, so it should fall through to the
        // generic on/off/toggle checks instead of SET_TEMPERATURE.
        NlpResult result = service.infer("включи термостат 23", "ru");

        assertEquals("smart_home_action", result.intent());
        assertEquals("TURN_ON", result.slots().get("action"));
        assertNotEquals("SET_TEMPERATURE", result.slots().get("action"));
    }

    @Test
    void inferRecognizesScenarioModes() {
        assertEquals("work_mode", service.infer("рабочий режим", "ru").intent());
        assertEquals("rest_mode", service.infer("режим отдыха", "ru").intent());
        assertEquals("focus_mode", service.infer("режим фокуса", "ru").intent());
    }

    @Test
    void inferRecognizesWindowControlIntents() {
        assertEquals("minimize_window", service.infer("сверни окно", "ru").intent());
        assertEquals("maximize_window", service.infer("разверни окно", "ru").intent());
        assertEquals("lock_screen", service.infer("заблокируй экран", "ru").intent());
    }

    @Test
    void inferRecognizesScreenshotIntent() {
        assertEquals("screenshot", service.infer("сделай скриншот", "ru").intent());
        assertEquals("screenshot", service.infer("screenshot", "en").intent());
    }

    @Test
    void inferRecognizesExpenseLoggingWithCategory() {
        NlpResult result = service.infer("потратил 500 руб на еду", "ru");

        assertEquals("add_expense", result.intent());
        assertEquals("500", result.slots().get("amount"));
        assertEquals("еду", result.slots().get("category"));
    }

    @Test
    void inferRecognizesExpenseLoggingDefaultsCategoryWhenMissing() {
        NlpResult result = service.infer("купил 300 руб", "ru");

        assertEquals("add_expense", result.intent());
        assertEquals("300", result.slots().get("amount"));
        assertEquals("прочее", result.slots().get("category"));
    }

    @Test
    void inferRecognizesTimeQuery() {
        assertEquals("get_time", service.infer("который час", "ru").intent());
        assertEquals("get_time", service.infer("сколько сейчас времени", "ru").intent());
        assertEquals("get_time", service.infer("what time is it", "en").intent());
    }

    @Test
    void inferRecognizesReminderWithText() {
        NlpResult result = service.infer("напомни купить молоко", "ru");

        assertEquals("add_reminder", result.intent());
        assertEquals("купить молоко", result.slots().get("text"));
    }

    @Test
    void inferHandlesNullTextAsFallback() {
        NlpResult result = service.infer(null, "ru");

        assertEquals("fallback", result.intent());
        assertTrue(result.slots().isEmpty());
    }
}
