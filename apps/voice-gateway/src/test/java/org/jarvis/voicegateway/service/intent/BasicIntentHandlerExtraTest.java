package org.jarvis.voicegateway.service.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supplements BasicIntentHandlerTest with the switch/if branches it does not
 * yet exercise: the remaining intent phrase groups, explicit-language handling,
 * STT-error normalization, and the small-talk wake-word heuristics.
 */
class BasicIntentHandlerExtraTest {

    private BasicIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BasicIntentHandler();
    }

    private IntentResult handle(String text) {
        return handler.handle(IntentRequest.builder().text(text).correlationId("corr-1").build());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @MethodSource("remainingPhrases")
    void matchesRemainingIntentPhrases(String text, String expectedAction) {
        IntentResult result = handle(text);

        assertTrue(result.isHandled(), "expected '" + text + "' to be handled");
        assertEquals(expectedAction, result.getAction());
    }

    private static Stream<Arguments> remainingPhrases() {
        return Stream.of(
                Arguments.of("громкость на максимум", "SET_VOLUME"),
                Arguments.of("volume max", "SET_VOLUME"),
                Arguments.of("открой блокнот", "OPEN_NOTEPAD"),
                Arguments.of("открой спотифай", "OPEN_URL"),
                Arguments.of("открой переводчик", "OPEN_URL"),
                Arguments.of("открой гугл", "OPEN_URL"),
                Arguments.of("открой вики", "OPEN_URL"),
                Arguments.of("открой гитхаб", "OPEN_URL"),
                Arguments.of("скопируй", "CLIPBOARD_COPY"),
                Arguments.of("вставь", "CLIPBOARD_PASTE"),
                Arguments.of("отмени", "UNDO_ACTION"),
                Arguments.of("переключи окно", "SWITCH_WINDOW"),
                Arguments.of("закрой окно", "CLOSE_WINDOW"),
                Arguments.of("обнови страницу", "REFRESH_PAGE"),
                Arguments.of("рабочий стол", "SHOW_DESKTOP"),
                Arguments.of("настройки", "OPEN_SETTINGS"),
                Arguments.of("смени язык", "SWITCH_LANGUAGE"),
                Arguments.of("скриншот", "SCREENSHOT"),
                Arguments.of("спящий режим", "SLEEP_MODE"),
                Arguments.of("выключи монитор", "MONITOR_OFF"),
                Arguments.of("сверни", "WINDOW_MINIMIZE"),
                Arguments.of("разверни", "WINDOW_MAXIMIZE"),
                Arguments.of("заблокируй", "LOCK_SCREEN"),
                Arguments.of("вечеринка", "HOUSE_PARTY"),
                Arguments.of("чистый лист", "CLEAN_SLATE"),
                Arguments.of("уютный вечер", "PROTOCOL_COZY_EVENING"),
                Arguments.of("у нас гости", "PROTOCOL_GUESTS"),
                Arguments.of("игровой режим", "GAME_MODE"),
                Arguments.of("шухер", "PROTOCOL_PANIC"),
                Arguments.of("как дела", "HOW_ARE_YOU"),
                Arguments.of("что делаешь", "WHAT_DOING"),
                Arguments.of("я вернулся", "WELCOME_HOME"),
                Arguments.of("мне скучно", "BORED"),
                Arguments.of("мне грустно", "CHEER_UP"),
                Arguments.of("я тебя люблю", "LOVE_RESPONSE"),
                Arguments.of("включи музыку", "PLAY_MUSIC"),
                Arguments.of("пока", "GOODBYE"));
    }

    @Test
    void explicitLanguageParameterTakesTheProvidedLanguageBranch() {
        // BasicIntentHandler lower-cases an explicitly supplied language instead of
        // auto-detecting it (LanguageDetector.detect() is not invoked on this branch);
        // the language isn't surfaced on IntentResult, so we assert the observable
        // effect: the handler still resolves the intent normally either way.
        IntentRequest request = IntentRequest.builder()
                .text("громче")
                .language("EN-us")
                .correlationId("corr-1")
                .build();

        IntentResult result = handler.handle(request);

        assertEquals("VOLUME_UP", result.getAction());
        assertTrue(result.isHandled());
    }

    @Test
    void languageIsAutoDetectedWhenNotProvided() {
        IntentRequest request = IntentRequest.builder()
                .text("louder please")
                .correlationId("corr-1")
                .build();

        IntentResult result = handler.handle(request);

        assertEquals("VOLUME_UP", result.getAction());
        assertTrue(result.isHandled());
    }

    @Test
    void sttMisrecognitionOfIncreaseVolumeIsNormalizedBeforeMatching() {
        // "влей громкость" is a known Vosk misrecognition of "увеличь громкость".
        IntentResult result = handle("влей громкость");

        assertEquals("VOLUME_UP", result.getAction());
    }

    @Test
    void fillerWordsAreStrippedBeforeMatching() {
        IntentResult result = handle("сделай, пожалуйста, громче сейчас");

        assertEquals("VOLUME_UP", result.getAction());
    }

    @Test
    void canHandleReturnsFalseForNullOrBlankText() {
        assertFalse(handler.canHandle(null));
        assertFalse(handler.canHandle(IntentRequest.builder().text(null).build()));
        assertFalse(handler.canHandle(IntentRequest.builder().text("   ").build()));
    }

    @Test
    void canHandleReturnsTrueForNonBlankText() {
        assertTrue(handler.canHandle(IntentRequest.builder().text("привет").build()));
    }

    @Test
    void smallTalkWakeWordAloneIsNotAnActionCommand() {
        assertEquals("SMALL_TALK_JARVIS", handle("jarvis").getAction());
        assertEquals("SMALL_TALK_JARVIS", handle("джарвис").getAction());
        assertEquals("SMALL_TALK_JARVIS", handle("Jarvis?").getAction());
    }

    @Test
    void wakeWordFollowedByCommandKeywordIsNotSmallTalk() {
        // "jarvis louder" should resolve to VOLUME_UP, not SMALL_TALK_JARVIS, because
        // containsActionKeywords() detects "louder".
        IntentResult result = handle("jarvis louder");

        assertEquals("VOLUME_UP", result.getAction());
    }
}
