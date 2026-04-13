package org.jarvis.voicegateway.service.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BasicIntentHandler.
 * 
 * Tests intent recognition patterns for Russian and English commands.
 * Covers volume control, media control, app launching, scenarios, and
 * greetings.
 */
class BasicIntentHandlerTest {

    private BasicIntentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BasicIntentHandler();
    }

    // ==================== Volume Control Tests ====================

    @Nested
    @DisplayName("Volume Control Intents")
    class VolumeControlTests {

        @Test
        @DisplayName("Russian: 'громче' -> VOLUME_UP")
        void volumeUpRussianGromche() {
            IntentResult result = handle("громче");
            assertIntent(result, "VOLUME_UP", true);
        }

        @Test
        @DisplayName("Russian: 'сделай громче' -> VOLUME_UP")
        void volumeUpRussianSdelayGromche() {
            IntentResult result = handle("сделай громче");
            assertIntent(result, "VOLUME_UP", true);
        }

        @Test
        @DisplayName("Russian: 'прибавь громкость' (desktop UI example) -> VOLUME_UP")
        void volumeUpRussianUiExample() {
            IntentResult result = handle("прибавь громкость");
            assertIntent(result, "VOLUME_UP", true);
        }

        @Test
        @DisplayName("Russian: 'сделать громче' (Vosk variation) -> VOLUME_UP")
        void volumeUpRussianVoskVariation() {
            IntentResult result = handle("сделать громче");
            assertIntent(result, "VOLUME_UP", true);
        }

        @Test
        @DisplayName("English: 'volume up' -> VOLUME_UP")
        void volumeUpEnglish() {
            IntentResult result = handle("volume up");
            assertIntent(result, "VOLUME_UP", true);
        }

        @Test
        @DisplayName("English: 'louder' -> VOLUME_UP")
        void volumeUpEnglishLouder() {
            IntentResult result = handle("louder");
            assertIntent(result, "VOLUME_UP", true);
        }

        @Test
        @DisplayName("Russian: 'тише' -> VOLUME_DOWN")
        void volumeDownRussian() {
            IntentResult result = handle("тише");
            assertIntent(result, "VOLUME_DOWN", true);
        }

        @Test
        @DisplayName("Russian: 'сделай тише' -> VOLUME_DOWN")
        void volumeDownRussianSdelayTishe() {
            IntentResult result = handle("сделай тише");
            assertIntent(result, "VOLUME_DOWN", true);
        }

        @Test
        @DisplayName("English: 'volume down' -> VOLUME_DOWN")
        void volumeDownEnglish() {
            IntentResult result = handle("volume down");
            assertIntent(result, "VOLUME_DOWN", true);
        }

        @Test
        @DisplayName("Russian: 'выключи звук' -> MUTE")
        void muteRussian() {
            IntentResult result = handle("выключи звук");
            assertIntent(result, "MUTE", true);
        }

        @Test
        @DisplayName("English: 'mute' -> MUTE")
        void muteEnglish() {
            IntentResult result = handle("mute");
            assertIntent(result, "MUTE", true);
        }
    }

    // ==================== Media Control Tests ====================

    @Nested
    @DisplayName("Media Control Intents")
    class MediaControlTests {

        @Test
        @DisplayName("Russian: 'следующий трек' -> MEDIA_NEXT")
        void nextTrackRussian() {
            IntentResult result = handle("следующий трек");
            assertIntent(result, "MEDIA_NEXT", true);
        }

        @Test
        @DisplayName("English: 'next track' -> MEDIA_NEXT")
        void nextTrackEnglish() {
            IntentResult result = handle("next track");
            assertIntent(result, "MEDIA_NEXT", true);
        }

        @Test
        @DisplayName("Russian: 'предыдущий трек' -> MEDIA_PREV")
        void prevTrackRussian() {
            IntentResult result = handle("предыдущий трек");
            assertIntent(result, "MEDIA_PREV", true);
        }

        @Test
        @DisplayName("Russian: 'пауза' -> PAUSE")
        void pauseRussian() {
            IntentResult result = handle("пауза");
            assertIntent(result, "PAUSE", true);
        }

        @Test
        @DisplayName("English: 'pause' -> PAUSE")
        void pauseEnglish() {
            IntentResult result = handle("pause");
            assertIntent(result, "PAUSE", true);
        }

        @Test
        @DisplayName("Russian: 'продолжи' -> PLAY")
        void resumeRussian() {
            IntentResult result = handle("продолжи");
            assertIntent(result, "PLAY", true);
        }

        @Test
        @DisplayName("English: 'resume' -> PLAY")
        void resumeEnglish() {
            IntentResult result = handle("resume");
            assertIntent(result, "PLAY", true);
        }
    }

    // ==================== App Launch Tests ====================

    @Nested
    @DisplayName("App Launch Intents")
    class AppLaunchTests {

        @Test
        @DisplayName("Russian: 'открой браузер' -> OPEN_BROWSER")
        void openBrowserRussian() {
            IntentResult result = handle("открой браузер");
            assertIntent(result, "OPEN_BROWSER", true);
        }

        @Test
        @DisplayName("English: 'open browser' -> OPEN_BROWSER")
        void openBrowserEnglish() {
            IntentResult result = handle("open browser");
            assertIntent(result, "OPEN_BROWSER", true);
        }

        @Test
        @DisplayName("Russian: 'открой терминал' -> OPEN_TERMINAL")
        void openTerminalRussian() {
            IntentResult result = handle("открой терминал");
            assertIntent(result, "OPEN_TERMINAL", true);
        }

        @Test
        @DisplayName("Russian: 'открой youtube' -> OPEN_YOUTUBE")
        void openYoutubeRussian() {
            IntentResult result = handle("открой youtube");
            assertIntent(result, "OPEN_YOUTUBE", true);
        }
    }

    // ==================== Small Talk / Greeting Tests ====================

    @Nested
    @DisplayName("Small Talk and Greetings")
    class SmallTalkTests {

        @Test
        @DisplayName("Russian: 'джарвис' (just wake word) -> SMALL_TALK_JARVIS")
        void wakeWordOnlyRussian() {
            IntentResult result = handle("джарвис");
            assertIntent(result, "SMALL_TALK_JARVIS", true);
        }

        @Test
        @DisplayName("English: 'jarvis' (just wake word) -> SMALL_TALK_JARVIS")
        void wakeWordOnlyEnglish() {
            IntentResult result = handle("jarvis");
            assertIntent(result, "SMALL_TALK_JARVIS", true);
        }

        @Test
        @DisplayName("Russian: 'не спишь' -> ARE_YOU_THERE")
        void areYouThereRussian() {
            IntentResult result = handle("не спишь");
            assertIntent(result, "ARE_YOU_THERE", true);
        }

        @Test
        @DisplayName("English: 'are you there' -> ARE_YOU_THERE")
        void areYouThereEnglish() {
            IntentResult result = handle("are you there");
            assertIntent(result, "ARE_YOU_THERE", true);
        }

        @Test
        @DisplayName("Russian: 'привет' -> GREETING")
        void greetingRussian() {
            IntentResult result = handle("привет");
            assertIntent(result, "GREETING", true);
        }

        @Test
        @DisplayName("English: 'hello' -> GREETING")
        void greetingEnglish() {
            IntentResult result = handle("hello");
            assertIntent(result, "GREETING", true);
        }

        @Test
        @DisplayName("Russian: 'спасибо' -> THANKS")
        void thanksRussian() {
            IntentResult result = handle("спасибо");
            assertIntent(result, "THANKS", true);
        }
    }

    // ==================== Scenario / Protocol Tests ====================

    @Nested
    @DisplayName("Scenario and Protocol Intents")
    class ScenarioTests {

        @Test
        @DisplayName("Russian: 'рабочий режим' -> WORK_MODE")
        void workModeRussian() {
            IntentResult result = handle("рабочий режим");
            assertIntent(result, "WORK_MODE", true);
        }

        @Test
        @DisplayName("English: 'work mode' -> WORK_MODE")
        void workModeEnglish() {
            IntentResult result = handle("work mode");
            assertIntent(result, "WORK_MODE", true);
        }

        @Test
        @DisplayName("Russian: 'режим фокусировки' -> FOCUS_MODE")
        void focusModeRussian() {
            IntentResult result = handle("режим фокусировки");
            assertIntent(result, "FOCUS_MODE", true);
        }
    }

    // ==================== Correlation ID Tests ====================

    @Nested
    @DisplayName("Correlation ID Propagation")
    class CorrelationIdTests {

        @Test
        @DisplayName("CorrelationId is preserved in result")
        void correlationIdPreserved() {
            String corrId = "test-corr-123";
            IntentResult result = handle("громче", corrId);
            assertEquals(corrId, result.getCorrelationId());
        }

        @Test
        @DisplayName("Null correlationId doesn't cause errors")
        void nullCorrelationIdHandled() {
            IntentRequest request = IntentRequest.builder()
                    .text("громче")
                    .correlationId(null)
                    .build();
            IntentResult result = handler.handle(request);
            assertNotNull(result);
            assertTrue(result.isHandled());
        }
    }

    // ==================== Unknown Command Tests ====================

    @Nested
    @DisplayName("Unknown Commands")
    class UnknownCommandTests {

        @Test
        @DisplayName("Unrecognized text returns handled=false")
        void unknownCommandNotHandled() {
            IntentResult result = handle("some random unrecognized command xyz");
            assertFalse(result.isHandled());
            assertEquals("UNKNOWN", result.getAction());
        }
    }

    // ==================== Helper Methods ====================

    private IntentResult handle(String text) {
        return handle(text, "test-correlation-id");
    }

    private IntentResult handle(String text, String correlationId) {
        IntentRequest request = IntentRequest.builder()
                .text(text)
                .correlationId(correlationId)
                .build();
        return handler.handle(request);
    }

    private void assertIntent(IntentResult result, String expectedAction, boolean expectedHandled) {
        assertNotNull(result, "Intent result should not be null");
        assertEquals(expectedHandled, result.isHandled(),
                "Intent handled flag mismatch for action: " + expectedAction);
        assertEquals(expectedAction, result.getAction(),
                "Action mismatch");
    }
}
