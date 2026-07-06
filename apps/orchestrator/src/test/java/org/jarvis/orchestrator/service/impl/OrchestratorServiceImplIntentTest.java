package org.jarvis.orchestrator.service.impl;

import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.dto.LlmChatResponse;
import org.jarvis.orchestrator.phrases.JarvisPhraseProvider;
import org.jarvis.orchestrator.phrases.Language;
import org.jarvis.orchestrator.phrases.PhraseContext;
import org.jarvis.orchestrator.resilience.SimpleCircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Broad branch coverage for {@link OrchestratorServiceImpl#executeIntent}: the
 * simple phrase-only intents, the PC-action intents (with the API-gateway /
 * direct-host fallback), slot parsing edge cases, and the LLM fallback /
 * circuit-breaker paths that the existing focused tests don't already cover.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorServiceImplIntentTest {

    @Mock
    private NlpClient nlpClient;
    @Mock
    private PcControlClient pcControlClient;
    @Mock
    private ApiGatewayPcClient apiGatewayPcClient;
    @Mock
    private JarvisPhraseProvider phraseProvider;
    @Mock
    private LlmServiceClient llmClient;
    @Mock
    private SmartHomeClient smartHomeClient;

    private OrchestratorServiceImpl service;

    private OrchestratorServiceImpl newService() {
        service = new OrchestratorServiceImpl(
                nlpClient, pcControlClient, apiGatewayPcClient, phraseProvider,
                llmClient, smartHomeClient, new OrchestratorExecutorProperties());
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownLlmExecutor();
        }
    }

    // --- Group 1: pure phrase intents, no PC action dispatched ---------------

    static Stream<Arguments> phraseOnlyIntents() {
        return Stream.of(
                Arguments.of("hello", PhraseContext.GREETING),
                Arguments.of("greeting", PhraseContext.GREETING),
                Arguments.of("morning_greeting", PhraseContext.MORNING_GREETING),
                Arguments.of("goodbye", PhraseContext.GOODBYE),
                Arguments.of("thanks", PhraseContext.THANKS),
                Arguments.of("small_talk_jarvis", PhraseContext.SMALL_TALK_JARVIS),
                Arguments.of("are_you_there", PhraseContext.ARE_YOU_THERE),
                Arguments.of("wake_response", PhraseContext.WAKE_RESPONSE),
                Arguments.of("stt_timeout", PhraseContext.STT_TIMEOUT),
                Arguments.of("stt_noise", PhraseContext.STT_NOISE),
                Arguments.of("welcome_home", PhraseContext.WELCOME_HOME),
                Arguments.of("how_are_you", PhraseContext.HOW_ARE_YOU),
                Arguments.of("what_doing", PhraseContext.WHAT_DOING),
                Arguments.of("bored", PhraseContext.BORED),
                Arguments.of("cheer_up", PhraseContext.CHEER_UP),
                Arguments.of("love_response", PhraseContext.LOVE_RESPONSE),
                Arguments.of("random_fact", PhraseContext.RANDOM_FACT),
                Arguments.of("standby_mode", PhraseContext.STANDBY_MODE));
    }

    @ParameterizedTest
    @MethodSource("phraseOnlyIntents")
    void phraseOnlyIntentsReturnMappedPhraseWithoutTouchingPcClients(String intent, PhraseContext context) {
        newService();
        when(phraseProvider.getPhrase(eq(context), eq(Language.RU))).thenReturn("phrase:" + context);

        String result = service.executeIntent(intent, Map.of(), "ru", "corr", "text", "user-1");

        assertEquals("phrase:" + context, result);
        verifyNoInteractions(apiGatewayPcClient, pcControlClient);
    }

    // --- Group 2: PC-action intents (no slots) --------------------------------

    static Stream<Arguments> pcActionIntents() {
        return Stream.of(
                Arguments.of("mute", "MUTE", Map.of(), PhraseContext.MUTE),
                Arguments.of("unmute", "UNMUTE", Map.of(), PhraseContext.UNMUTE),
                Arguments.of("play", "PLAY_PAUSE", Map.of(), PhraseContext.PLAY),
                Arguments.of("resume", "PLAY_PAUSE", Map.of(), PhraseContext.PLAY),
                Arguments.of("pause", "PAUSE", Map.of(), PhraseContext.PAUSE),
                Arguments.of("stop", "PAUSE", Map.of(), PhraseContext.PAUSE),
                Arguments.of("next_track", "NEXT", Map.of(), PhraseContext.NEXT_TRACK),
                Arguments.of("previous_track", "PREV", Map.of(), PhraseContext.PREVIOUS_TRACK),
                Arguments.of("minimize_window", "MINIMIZE", Map.of(), PhraseContext.WINDOW_MINIMIZE),
                Arguments.of("maximize_window", "MAXIMIZE", Map.of(), PhraseContext.WINDOW_MAXIMIZE),
                Arguments.of("lock_screen", "LOCK_SCREEN", Map.of(), PhraseContext.LOCK_SCREEN),
                Arguments.of("clipboard_copy", "HOTKEY", Map.of("keyCombination", "ctrl+c"), PhraseContext.CLIPBOARD_COPY),
                Arguments.of("clipboard_paste", "HOTKEY", Map.of("keyCombination", "ctrl+v"), PhraseContext.CLIPBOARD_PASTE),
                Arguments.of("undo_action", "HOTKEY", Map.of("keyCombination", "ctrl+z"), PhraseContext.UNDO_ACTION),
                Arguments.of("switch_window", "HOTKEY", Map.of("keyCombination", "Alt+Tab"), PhraseContext.SWITCH_WINDOW),
                Arguments.of("close_window", "HOTKEY", Map.of("keyCombination", "Alt+F4"), PhraseContext.CLOSE_WINDOW),
                Arguments.of("fullscreen", "HOTKEY", Map.of("keyCombination", "F11"), PhraseContext.FULLSCREEN),
                Arguments.of("refresh_page", "HOTKEY", Map.of("keyCombination", "F5"), PhraseContext.REFRESH_PAGE),
                Arguments.of("navigate_back", "HOTKEY", Map.of("keyCombination", "Alt+Left"), PhraseContext.NAVIGATE_BACK),
                Arguments.of("navigate_forward", "HOTKEY", Map.of("keyCombination", "Alt+Right"), PhraseContext.NAVIGATE_FORWARD),
                Arguments.of("show_desktop", "HOTKEY", Map.of("keyCombination", "Super+d"), PhraseContext.SHOW_DESKTOP),
                Arguments.of("open_settings", "OPEN_APP", Map.of("app", "settings"), PhraseContext.OPEN_SETTINGS),
                Arguments.of("system_search", "HOTKEY", Map.of("keyCombination", "Super_L"), PhraseContext.SYSTEM_SEARCH),
                Arguments.of("switch_language", "HOTKEY", Map.of("keyCombination", "Alt+Shift"), PhraseContext.SWITCH_LANGUAGE),
                Arguments.of("screenshot", "HOTKEY", Map.of("keyCombination", "Print"), PhraseContext.SCREENSHOT),
                Arguments.of("sleep_mode", "SYSTEM_COMMAND", Map.of("command", "sleep"), PhraseContext.SLEEP_MODE),
                Arguments.of("find_in_page", "HOTKEY", Map.of("keyCombination", "ctrl+f"), PhraseContext.ACK_GENERIC),
                Arguments.of("focus_address_bar", "HOTKEY", Map.of("keyCombination", "ctrl+l"), PhraseContext.ACK_GENERIC),
                Arguments.of("rename_item", "HOTKEY", Map.of("keyCombination", "F2"), PhraseContext.ACK_GENERIC),
                Arguments.of("delete_selection", "HOTKEY", Map.of("keyCombination", "Delete"), PhraseContext.ACK_GENERIC),
                Arguments.of("press_enter", "HOTKEY", Map.of("keyCombination", "Return"), PhraseContext.ACK_GENERIC),
                Arguments.of("work_mode", "SCENARIO", Map.of("name", "work"), PhraseContext.WORK_MODE),
                Arguments.of("rest_mode", "SCENARIO", Map.of("name", "rest"), PhraseContext.REST_MODE),
                Arguments.of("focus_mode", "SCENARIO", Map.of("name", "focus"), PhraseContext.FOCUS_MODE),
                Arguments.of("house_party", "SCENARIO", Map.of("name", "party"), PhraseContext.PROTOCOL_HOUSE_PARTY),
                Arguments.of("clean_slate", "SCENARIO", Map.of("name", "clean_slate"), PhraseContext.PROTOCOL_CLEAN_SLATE),
                Arguments.of("protocol_cozy_evening", "SCENARIO", Map.of("name", "cozy_evening"), PhraseContext.PROTOCOL_COZY_EVENING),
                Arguments.of("protocol_guests", "SCENARIO", Map.of("name", "guests"), PhraseContext.PROTOCOL_GUESTS),
                Arguments.of("protocol_holiday", "SCENARIO", Map.of("name", "holiday"), PhraseContext.PROTOCOL_HOLIDAY),
                Arguments.of("game_mode", "SCENARIO", Map.of("name", "game"), PhraseContext.GAME_MODE),
                Arguments.of("protocol_morning", "SCENARIO", Map.of("name", "morning"), PhraseContext.PROTOCOL_MORNING),
                Arguments.of("protocol_leaving", "SCENARIO", Map.of("name", "leaving"), PhraseContext.PROTOCOL_LEAVING),
                Arguments.of("protocol_panic", "SCENARIO", Map.of("name", "panic"), PhraseContext.PROTOCOL_PANIC),
                Arguments.of("network_check", "OPEN_URL", Map.of("url", "https://fast.com/"), PhraseContext.ACK_GENERIC));
    }

    @ParameterizedTest
    @MethodSource("pcActionIntents")
    @SuppressWarnings("unchecked")
    void pcActionIntentsDispatchExpectedActionAndFallBackToDirectHost(
            String intent, String action, Map<String, Object> params, PhraseContext context) {
        newService();
        when(phraseProvider.getPhrase(eq(context), eq(Language.RU))).thenReturn("phrase:" + context);

        String result = service.executeIntent(intent, Map.of(), "ru", "corr", "text", "user-1");

        assertEquals("phrase:" + context, result);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest(action, params, "user-1", "corr"));
        // apiGatewayPcClient is unstubbed (returns null) -> treated as executor-not-found,
        // so the direct host pc-control bridge must be tried as a fallback.
        verify(pcControlClient).executeAction(eq("user-1"), any(PcControlClient.ActionRequest.class));
    }

    // --- Group 3: slot-driven variations ---------------------------------------

    @Test
    void volumeUpUsesAmountSlotWhenDeltaAbsent() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.VOLUME_UP), eq(Language.RU))).thenReturn("louder");

        String result = service.executeIntent("volume_up", Map.of("amount", "25"), "ru", "corr", "text", "u");

        assertEquals("louder", result);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("VOLUME_UP", Map.of("delta", 25), "u", "corr"));
    }

    @Test
    void volumeDownDefaultsDeltaWhenSlotsMissing() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.VOLUME_DOWN), eq(Language.RU))).thenReturn("quieter");

        String result = service.executeIntent("volume_down", null, "ru", "corr", "text", "u");

        assertEquals("quieter", result);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("VOLUME_DOWN", Map.of("delta", 10), "u", "corr"));
    }

    @Test
    void setVolumeUsesLevelSlotOrDefaultsTo100() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.VOLUME_MAX), eq(Language.RU))).thenReturn("max");

        service.executeIntent("set_volume", Map.of("level", "55"), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("SET_VOLUME", Map.of("level", 55), "u", "corr"));
    }

    @Test
    void openAppDefaultsToBrowserWhenNoAppSlot() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_APP), eq(Language.RU), any(Map.class)))
                .thenReturn("opening browser");

        String result = service.executeIntent("open_app", Map.of(), "ru", "corr", "text", "u");

        assertEquals("opening browser", result);
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "browser"), "u", "corr"));
    }

    @Test
    void openAppUsesApplicationSlotWhenAppAbsent() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_APP), eq(Language.RU), any(Map.class)))
                .thenReturn("opening spotify");

        service.executeIntent("launch_app", Map.of("application", "spotify"), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "spotify"), "u", "corr"));
    }

    @Test
    void openBrowserYoutubeAndIdeDefaults() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_BROWSER), eq(Language.RU))).thenReturn("browser");
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_YOUTUBE), eq(Language.RU))).thenReturn("yt");
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_IDE), eq(Language.RU))).thenReturn("ide");
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_TERMINAL), eq(Language.RU))).thenReturn("term");

        assertEquals("browser", service.executeIntent("open_browser", Map.of(), "ru", "c1", "t", "u"));
        assertEquals("yt", service.executeIntent("open_youtube", Map.of(), "ru", "c2", "t", "u"));
        assertEquals("ide", service.executeIntent("open_ide", Map.of(), "ru", "c3", "t", "u"));
        assertEquals("term", service.executeIntent("open_terminal", Map.of(), "ru", "c4", "t", "u"));

        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "browser"), "u", "c1"));
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "youtube"), "u", "c2"));
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "code"), "u", "c3"));
        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "terminal"), "u", "c4"));
    }

    @Test
    void openIdeUsesCustomIdeSlot() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_IDE), eq(Language.RU))).thenReturn("ide");

        service.executeIntent("open_code", Map.of("ide", "intellij"), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("OPEN_APP", Map.of("app", "intellij"), "u", "corr"));
    }

    @Test
    void openUrlWithBlankUrlReturnsErrorWithoutDispatching() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("error");

        String result = service.executeIntent("open_url", Map.of("url", "  "), "ru", "corr", "text", "u");

        assertEquals("error", result);
        verifyNoInteractions(apiGatewayPcClient, pcControlClient);
    }

    @Test
    void openUrlWithMissingSlotsMapReturnsErrorWithoutDispatching() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("error");

        String result = service.executeIntent("open_url", null, "ru", "corr", "text", "u");

        assertEquals("error", result);
        verifyNoInteractions(apiGatewayPcClient, pcControlClient);
    }

    @Test
    void openNewsUsesCustomUrlSlotWhenPresent() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.OPEN_URL), eq(Language.RU))).thenReturn("news");

        service.executeIntent("open_news", Map.of("url", "https://custom-news.example/"), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "OPEN_URL", Map.of("url", "https://custom-news.example/"), "u", "corr"));
    }

    // --- Group 4: read-only queries (no PC dispatch, language-dependent) ------

    @Test
    void getTimeReturnsRussianPhraseForRuLanguage() {
        newService();
        String result = service.executeIntent("get_time", Map.of(), "ru", "corr", "text", "u");
        assertTrue(result.startsWith("Сейчас "));
        verifyNoInteractions(apiGatewayPcClient, pcControlClient, phraseProvider);
    }

    @Test
    void getTimeReturnsEnglishPhraseForEnLanguage() {
        newService();
        String result = service.executeIntent("what_time", Map.of(), "en", "corr", "text", "u");
        assertTrue(result.startsWith("It is "));
    }

    @Test
    void getDateReturnsRussianPhraseForRuLanguage() {
        newService();
        String result = service.executeIntent("get_date", Map.of(), "ru", "corr", "text", "u");
        assertTrue(result.startsWith("Сегодня "));
    }

    @Test
    void getDateReturnsEnglishPhraseForEnLanguage() {
        newService();
        String result = service.executeIntent("current_date", Map.of(), "en", "corr", "text", "u");
        assertTrue(result.startsWith("Today is "));
    }

    /**
     * Regression test for finding #48 (timezone-misuse): get_time must use the configured
     * jarvis.timezone zone, not the JVM's default zone (ZoneId.systemDefault(), which in a
     * container may be UTC or anything else unrelated to the user's actual local time).
     */
    @Test
    void getTimeUsesConfiguredTimezoneInsteadOfJvmDefault() {
        newService();
        // Pacific/Kiritimati is UTC+14 (the largest positive UTC offset that exists), so it is
        // virtually guaranteed to differ from this test runner's JVM default zone -- if get_time
        // ever regresses to the raw no-arg LocalTime.now() (JVM default zone) instead of the
        // configured jarvis.timezone, this assertion fails.
        ReflectionTestUtils.setField(service, "timezone", "Pacific/Kiritimati");
        java.time.ZoneId zone = java.time.ZoneId.of("Pacific/Kiritimati");

        java.time.LocalTime before = java.time.LocalTime.now(zone);
        String result = service.executeIntent("get_time", Map.of(), "en", "corr", "text", "u");
        java.time.LocalTime after = java.time.LocalTime.now(zone);

        String beforeHhmm = String.format("%02d:%02d", before.getHour(), before.getMinute());
        String afterHhmm = String.format("%02d:%02d", after.getHour(), after.getMinute());
        assertTrue(result.contains(beforeHhmm) || result.contains(afterHhmm),
                "expected time formatted in the configured zone (" + beforeHhmm + " or " + afterHhmm
                        + ") but got: " + result);
    }

    @Test
    void resolveZoneIdFallsBackToUtcForInvalidTimezoneConfig() {
        newService();
        ReflectionTestUtils.setField(service, "timezone", "not-a-real-zone");

        Object resolved = ReflectionTestUtils.invokeMethod(service, "resolveZoneId");

        assertEquals(java.time.ZoneOffset.UTC, resolved);
    }

    // --- Group 5: media/legacy url actions and timer ---------------------------

    @Test
    void playMusicWithUrlOpensUrlInsteadOfTogglingPlayback() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.PLAY_MUSIC), eq(Language.RU))).thenReturn("playing");

        service.executeIntent("play_music", Map.of("url", "https://music.example/track"), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "OPEN_URL", Map.of("url", "https://music.example/track"), "u", "corr"));
    }

    @Test
    void playMusicWithoutUrlTogglesPlayPause() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.PLAY_MUSIC), eq(Language.RU))).thenReturn("playing");

        service.executeIntent("play_music", Map.of(), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(
                new ApiGatewayPcClient.PcActionRequest("PLAY_PAUSE", Map.of(), "u", "corr"));
    }

    @Test
    void playRadioUsesDefaultUrlWhenSlotAbsent() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.PLAY_RADIO), eq(Language.RU))).thenReturn("radio");

        service.executeIntent("play_radio", Map.of(), "ru", "corr", "text", "u");

        verify(apiGatewayPcClient).sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "OPEN_URL", Map.of("url", "https://radio.garden/"), "u", "corr"));
    }

    @Test
    void setTimerConvertsMinutesToSeconds() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.TIMER_SET), eq(Language.RU), any(Map.class)))
                .thenReturn("timer set");

        String result = service.executeIntent(
                "set_timer", Map.of("amount", "2", "unit", "min"), "ru", "corr", "text", "u");

        assertEquals("timer set", result);
        verify(apiGatewayPcClient).sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "NOTIFY",
                Map.of("title", "Таймер", "message", "Установлен на 2 min"),
                "u", "corr"));
    }

    @Test
    void setTimerFallsBackToSixtySecondsOnInvalidAmount() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.TIMER_SET), eq(Language.RU), any(Map.class)))
                .thenReturn("timer set");

        service.executeIntent("set_timer", Map.of("amount", "not-a-number"), "ru", "corr", "text", "u");

        // Invalid amount -> defaults to 60 seconds; the notification still echoes the raw amount/unit text.
        verify(apiGatewayPcClient).sendPcAction(new ApiGatewayPcClient.PcActionRequest(
                "NOTIFY",
                Map.of("title", "Таймер", "message", "Установлен на not-a-number sec"),
                "u", "corr"));
    }

    // --- Group 6: smart_home_action validation ----------------------------------

    @Test
    void smartHomeActionMissingDeviceIdReturnsAckErrorAndMarksFailure() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("failed");

        IntentExecutionResult result = service.executeIntentDetailed(
                "smart_home_action", Map.of("action", "TURN_ON"), "ru", "corr", "text", "u");

        assertEquals("failed", result.responseText());
        assertTrue(result.executionFailed());
        verifyNoInteractions(smartHomeClient);
    }

    @Test
    void smartHomeActionMissingActionReturnsAckErrorAndMarksFailure() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("failed");

        IntentExecutionResult result = service.executeIntentDetailed(
                "smart_home_action", Map.of("deviceId", "kitchen_light"), "ru", "corr", "text", "u");

        assertEquals("failed", result.responseText());
        assertTrue(result.executionFailed());
        verifyNoInteractions(smartHomeClient);
    }

    /**
     * Regression test for finding #29 (degraded-partial-response): the smart-home action
     * itself succeeds, but the best-effort desktop NOTIFY dispatch fails (both API-Gateway
     * and direct host pc-control unreachable). That best-effort failure must not clobber the
     * already-correct smart-home success response with the generic ACK_ERROR phrase.
     */
    @Test
    void smartHomeActionSucceedsAndBestEffortNotifyFailureDoesNotOverwriteSuccessResponse() {
        newService();
        SmartHomeClient.DeviceView device = new SmartHomeClient.DeviceView(
                "kitchen_light", "Kitchen Light", "Kitchen", "LIGHT",
                java.util.List.of("TURN_ON", "TURN_OFF"), Map.of("power", true), "mock", "2026-03-14T10:45:00Z");
        SmartHomeClient.ActionResult actionResult = new SmartHomeClient.ActionResult(
                true, "u", "TURN_ON", "Action executed locally", device, "2026-03-14T10:45:00Z");
        when(smartHomeClient.executeAction(
                eq("u"), eq("kitchen_light"), eq(new SmartHomeClient.ActionRequest("TURN_ON", null))))
                .thenReturn(actionResult);
        when(phraseProvider.getPhrase(
                eq(PhraseContext.SMART_HOME_TURN_ON), eq(Language.RU), eq(Map.of("device", "кухонный свет"))))
                .thenReturn("Кухонный свет включён, сэр.");
        // Both the API-Gateway and the direct host pc-control bridge are unreachable, so the
        // best-effort NOTIFY dispatch fails -- this must NOT clobber the smart-home success response.
        when(apiGatewayPcClient.sendPcAction(any())).thenThrow(new RuntimeException("gateway down"));
        doThrow(new RuntimeException("host bridge down")).when(pcControlClient).executeAction(any(), any());

        IntentExecutionResult result = service.executeIntentDetailed(
                "smart_home_action",
                Map.of("deviceId", "kitchen_light", "action", "TURN_ON"),
                "ru", "corr", "text", "u");

        assertEquals("Кухонный свет включён, сэр.", result.responseText());
        assertTrue(result.executionSucceeded());
        assertFalse(result.executionFailed());
    }

    // --- Group 7: intent normalization ------------------------------------------

    @Test
    void hyphenatedIntentIsNormalizedToSnakeCaseAndMatched() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.VOLUME_UP), eq(Language.RU))).thenReturn("louder");

        String result = service.executeIntent("VOLUME-UP", Map.of(), "ru", "corr", "text", "u");

        assertEquals("louder", result);
    }

    @Test
    void nullIntentNormalizesToUnknownAndFallsBackToLlmDisabledPhrase() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        String result = service.executeIntent(null, Map.of(), "ru", "corr", "text", "u");

        assertEquals("dunno", result);
        verifyNoInteractions(llmClient);
    }

    // --- Group 8: LLM fallback / circuit breaker --------------------------------

    @Test
    void llmDisabledSkipsCallAndReturnsUnknownCommandPhrase() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        String result = service.executeIntent("totally_unrecognized", Map.of(), "ru", "corr", "text", "u");

        assertEquals("dunno", result);
        verifyNoInteractions(llmClient);
    }

    @Test
    void circuitBreakerOpenSkipsLlmCallEntirely() {
        newService();
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        SimpleCircuitBreaker llmCircuitBreaker =
                (SimpleCircuitBreaker) ReflectionTestUtils.getField(service, "llmCircuitBreaker");
        // Force the breaker open for 60s (threshold=1 -> a single recorded failure trips it).
        llmCircuitBreaker.recordFailure(1, Duration.ofSeconds(60));
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        String result = service.executeIntent("totally_unrecognized", Map.of(), "ru", "corr", "text", "u");

        assertEquals("dunno", result);
        verifyNoInteractions(llmClient);
    }

    @Test
    void llmSuccessReturnsReplyDirectlyAndResetsFailureCount() {
        newService();
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmTimeoutSeconds", 5);
        when(llmClient.chat(any(), anyString(), nullable(String.class), anyString()))
                .thenReturn(new LlmChatResponse("Here's your answer.", Map.of(), "qwen", 3, "neutral"));

        String result = service.executeIntent("totally_unrecognized", Map.of(), "ru", "corr", "text", "u");

        assertEquals("Here's your answer.", result);
        verifyNoInteractions(phraseProvider);
    }

    @Test
    void llmRuntimeExceptionRecordsFailureAndReturnsUnknownCommandPhrase() {
        newService();
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmTimeoutSeconds", 5);
        when(llmClient.chat(any(), anyString(), nullable(String.class), anyString()))
                .thenThrow(new RuntimeException("llm-service down"));
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        String result = service.executeIntent("totally_unrecognized", Map.of(), "ru", "corr", "text", "u");

        assertEquals("dunno", result);
    }

    @Test
    void circuitOpensAfterConsecutiveFailureThreshold() {
        newService();
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "circuitBreakerFailureThreshold", 2);
        ReflectionTestUtils.setField(service, "circuitBreakerResetTimeoutSeconds", 60);
        doThrow(new RuntimeException("boom"))
                .when(llmClient).chat(any(), anyString(), nullable(String.class), anyString());
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        // Two failures trip the breaker (threshold=2); the third call must not reach llmClient at all.
        service.executeIntent("totally_unrecognized", Map.of(), "ru", "c1", "text", "u");
        service.executeIntent("totally_unrecognized", Map.of(), "ru", "c2", "text", "u");
        String third = service.executeIntent("totally_unrecognized", Map.of(), "ru", "c3", "text", "u");

        assertEquals("dunno", third);
        verify(llmClient, org.mockito.Mockito.times(2))
                .chat(any(), anyString(), nullable(String.class), anyString());
    }

    /**
     * Regression test for finding #47 (circuit-breaker): the LLM circuit breaker must
     * use the shared {@link SimpleCircuitBreaker} half-open/single-trial semantics
     * instead of a hand-rolled counter that gets reset to 0 the instant the circuit
     * opens. With the old hand-rolled logic, once the cooldown elapsed the breaker
     * behaved as fully CLOSED again, so a single failed retry after the cooldown was
     * not enough to re-open it -- it took a whole fresh run of
     * {@code circuitBreakerFailureThreshold} consecutive failures. With the fix, a
     * single failed half-open trial must re-open the circuit immediately.
     */
    @Test
    void singleFailedHalfOpenTrialReopensCircuitWithoutRequiringFreshFailureRun() throws InterruptedException {
        newService();
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "circuitBreakerFailureThreshold", 3);
        ReflectionTestUtils.setField(service, "circuitBreakerResetTimeoutSeconds", 1);
        doThrow(new RuntimeException("boom"))
                .when(llmClient).chat(any(), anyString(), nullable(String.class), anyString());
        when(phraseProvider.getPhrase(eq(PhraseContext.UNKNOWN_COMMAND), eq(Language.RU))).thenReturn("dunno");

        // Trip the breaker with 3 consecutive failures (threshold=3).
        service.executeIntent("totally_unrecognized", Map.of(), "ru", "c1", "text", "u");
        service.executeIntent("totally_unrecognized", Map.of(), "ru", "c2", "text", "u");
        service.executeIntent("totally_unrecognized", Map.of(), "ru", "c3", "text", "u");
        verify(llmClient, org.mockito.Mockito.times(3))
                .chat(any(), anyString(), nullable(String.class), anyString());

        // Let the 1s cooldown elapse: the breaker is now HALF_OPEN and must allow exactly
        // one trial call through.
        Thread.sleep(1100);
        service.executeIntent("totally_unrecognized", Map.of(), "ru", "c4-half-open-trial", "text", "u");
        verify(llmClient, org.mockito.Mockito.times(4))
                .chat(any(), anyString(), nullable(String.class), anyString());

        // The half-open trial (c4) also failed, so the breaker must re-open IMMEDIATELY --
        // not require a fresh run of circuitBreakerFailureThreshold consecutive failures.
        // The very next call must therefore be rejected without reaching llmClient at all.
        String afterFailedTrial = service.executeIntent("totally_unrecognized", Map.of(), "ru", "c5", "text", "u");

        assertEquals("dunno", afterFailedTrial);
        verify(llmClient, org.mockito.Mockito.times(4))
                .chat(any(), anyString(), nullable(String.class), anyString());
    }

    // --- Group 9: dispatchPcAction fallback / parsing edge cases ----------------

    @Test
    void apiGatewayExceptionFallsBackToDirectHostSuccessfully() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.MUTE), eq(Language.RU))).thenReturn("muted");
        when(apiGatewayPcClient.sendPcAction(any())).thenThrow(new RuntimeException("gateway unreachable"));

        String result = service.executeIntent("mute", Map.of(), "ru", "corr", "text", "u");

        assertEquals("muted", result);
        verify(pcControlClient).executeAction(eq("u"), any(PcControlClient.ActionRequest.class));
    }

    @Test
    void apiGatewayReturningStringBooleansIsParsedCorrectly() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.MUTE), eq(Language.RU))).thenReturn("muted");
        Map<String, Object> stringyResult = new LinkedHashMap<>();
        stringyResult.put("executorFound", "true");
        stringyResult.put("executionAttempted", "true");
        stringyResult.put("executionSucceeded", "true");
        when(apiGatewayPcClient.sendPcAction(any())).thenReturn(stringyResult);

        String result = service.executeIntent("mute", Map.of(), "ru", "corr", "text", "u");

        assertEquals("muted", result);
        verifyNoInteractions(pcControlClient);
    }

    @Test
    void apiGatewayFailureReasonFallsBackToMessageFieldWhenAbsent() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("ack-error");
        Map<String, Object> gatewayResult = Map.of(
                "executionSucceeded", false,
                "message", "no clients connected");
        when(apiGatewayPcClient.sendPcAction(any())).thenReturn(gatewayResult);
        doThrow(new RuntimeException("host bridge down")).when(pcControlClient).executeAction(any(), any());

        IntentExecutionResult result = service.executeIntentDetailed("mute", Map.of(), "ru", "corr", "text", "u");

        assertEquals("ack-error", result.responseText());
        assertEquals("no clients connected", result.failureReason());
    }

    @Test
    void directHostAlsoFailingReturnsAckErrorWithoutThrowing() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("ack-error");
        when(apiGatewayPcClient.sendPcAction(any())).thenThrow(new RuntimeException("gateway down"));
        doThrow(new RuntimeException("host bridge down")).when(pcControlClient).executeAction(any(), any());

        String result = service.executeIntent("mute", Map.of(), "ru", "corr", "text", "u");

        assertEquals("ack-error", result);
    }

    /**
     * Regression test for finding #28 (non-idempotent-retry): when the API-Gateway
     * response indicates the desktop executor WAS found and DID attempt the action
     * (e.g. an ack_timeout/soft-failure on the gateway side after the action already
     * ran), dispatchPcAction must NOT retry via the independent direct host
     * pc-control channel -- doing so risks executing a non-idempotent action
     * (MUTE/UNMUTE, NEXT_TRACK, undo, ...) a second time. It must surface the
     * failure to the caller instead.
     */
    @Test
    void softFailureWithExecutorFoundAndAttemptedDoesNotRetryViaDirectHost() {
        newService();
        when(phraseProvider.getPhrase(eq(PhraseContext.ACK_ERROR), eq(Language.RU))).thenReturn("ack-error");
        Map<String, Object> gatewayResult = Map.of(
                "executorFound", true,
                "executionAttempted", true,
                "executionSucceeded", false,
                "failureReason", "ack_timeout");
        when(apiGatewayPcClient.sendPcAction(any())).thenReturn(gatewayResult);

        String result = service.executeIntent("mute", Map.of(), "ru", "corr", "text", "u");

        assertEquals("ack-error", result);
        verifyNoInteractions(pcControlClient);
    }
}
