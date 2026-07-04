package org.jarvis.voicegateway.service;

import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalIntentExecutionServiceTest {

    @Mock
    private PcControlActionGateway pcControlActionGateway;
    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;

    private LocalIntentExecutionService service;

    @BeforeEach
    void setUp() {
        service = new LocalIntentExecutionService(pcControlActionGateway, smartHomeActionGateway);
    }

    private PcControlActionGateway.DispatchResult success() {
        return new PcControlActionGateway.DispatchResult("OK", true, true, true, false, null, Map.of());
    }

    private PcControlActionGateway.DispatchResult failure() {
        return new PcControlActionGateway.DispatchResult("ERR", true, true, false, true, "boom", Map.of());
    }

    @Test
    void responseOnlyActionsIgnoreExecutorsAndReturnLocalizedText() {
        LocalIntentExecutionService.ExecutionResult ru = service.execute("greeting", Map.of(), "ru", "corr-1", "user-1");
        assertEquals("Привет, сэр.", ru.responseText());
        assertTrue(ru.actionResolved());
        assertTrue(ru.executionSucceeded());
        assertFalse(ru.executorFound());

        LocalIntentExecutionService.ExecutionResult en = service.execute("greeting", Map.of(), "en-US", "corr-1", "user-1");
        assertEquals("Hello, sir.", en.responseText());
    }

    @Test
    void goodbyeThanksAndWakeResponsesAreLocalOnly() {
        assertEquals("До связи, сэр.", service.execute("goodbye", Map.of(), "ru", null, null).responseText());
        assertEquals("Always at your service, sir.", service.execute("thanks", Map.of(), "en", null, null).responseText());
        assertEquals("Да, сэр.", service.execute("wake_response", Map.of(), "ru", null, null).responseText());
        assertEquals("I'm here, sir.", service.execute("are_you_there", Map.of(), "en", null, null).responseText());
        assertEquals("С возвращением, сэр.", service.execute("welcome_home", Map.of(), "ru", null, null).responseText());
        assertEquals("Все системы в норме, сэр.", service.execute("how_are_you", Map.of(), "ru", null, null).responseText());
        assertEquals("Держу системы под контролем, сэр.", service.execute("what_doing", Map.of(), "ru", null, null).responseText());
        assertEquals("Могу запустить музыку или игру, сэр.", service.execute("bored", Map.of(), "ru", null, null).responseText());
        assertEquals("Я рядом, сэр.", service.execute("cheer_up", Map.of(), "ru", null, null).responseText());
        assertEquals("Это взаимно, сэр.", service.execute("love_response", Map.of(), "ru", null, null).responseText());
        assertEquals("Сэр, я вас плохо расслышал.", service.execute("stt_timeout", Map.of(), "ru", null, null).responseText());
        assertEquals("Похоже, это был только шум.", service.execute("stt_noise", Map.of(), "ru", null, null).responseText());
    }

    @Test
    void actionIsCaseAndHyphenNormalized() {
        when(pcControlActionGateway.dispatch(eq("PLAY_PAUSE"), anyMap(), any(), any())).thenReturn(success());

        LocalIntentExecutionService.ExecutionResult result = service.execute("PLAY-MUSIC", Map.of(), "ru", "corr-1", "user-1");

        assertTrue(result.actionResolved());
        verify(pcControlActionGateway).dispatch(eq("PLAY_PAUSE"), anyMap(), eq("user-1"), eq("corr-1"));
    }

    @Test
    void nullOrBlankActionNormalizesToUnknownAndFalls() {
        LocalIntentExecutionService.ExecutionResult result = service.execute(null, Map.of(), "ru", "corr-1", "user-1");

        assertFalse(result.actionResolved());
        assertEquals("LOCAL_FALLBACK_UNSUPPORTED", result.failureReason());
    }

    @Test
    void unsupportedActionReturnsUnsupportedResult() {
        LocalIntentExecutionService.ExecutionResult result = service.execute("some_unknown_action", Map.of(), "ru", "corr-1", "user-1");

        assertFalse(result.actionResolved());
        assertFalse(result.executorFound());
        assertEquals("LOCAL_FALLBACK_UNSUPPORTED", result.failureReason());
    }

    @Test
    void playMusicDispatchesPlayPauseWithNoParams() {
        when(pcControlActionGateway.dispatch(eq("PLAY_PAUSE"), eq(Map.of()), eq("user-1"), eq("corr-1")))
                .thenReturn(success());

        LocalIntentExecutionService.ExecutionResult result = service.execute("play_music", Map.of(), "ru", "corr-1", "user-1");

        assertEquals("Запускаю музыку.", result.responseText());
        assertTrue(result.executionSucceeded());
    }

    @Test
    void playRadioUsesUrlParamOrDefault() {
        when(pcControlActionGateway.dispatch(eq("OPEN_URL"), eq(Map.of("url", "https://radio.garden/")), any(), any()))
                .thenReturn(success());

        service.execute("play_radio", Map.of(), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("OPEN_URL"), eq(Map.of("url", "https://radio.garden/")), any(), any());
    }

    @Test
    void playRadioUsesProvidedUrlParam() {
        when(pcControlActionGateway.dispatch(eq("OPEN_URL"), eq(Map.of("url", "https://myradio.example/")), any(), any()))
                .thenReturn(success());

        service.execute("play_radio", Map.of("url", "https://myradio.example/"), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("OPEN_URL"), eq(Map.of("url", "https://myradio.example/")), any(), any());
    }

    @Test
    void volumeUpUsesDeltaParamWhenPresent() {
        when(pcControlActionGateway.dispatch(eq("VOLUME_UP"), eq(Map.of("delta", 25)), any(), any()))
                .thenReturn(success());

        service.execute("volume_up", Map.of("delta", "25"), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("VOLUME_UP"), eq(Map.of("delta", 25)), any(), any());
    }

    @Test
    void volumeUpFallsBackToDefaultDeltaWhenParamNotParseable() {
        when(pcControlActionGateway.dispatch(eq("VOLUME_UP"), eq(Map.of("delta", 10)), any(), any()))
                .thenReturn(success());

        service.execute("change_volume", Map.of("delta", "not-a-number"), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("VOLUME_UP"), eq(Map.of("delta", 10)), any(), any());
    }

    @Test
    void volumeUpUsesAmountAliasWhenDeltaMissing() {
        when(pcControlActionGateway.dispatch(eq("VOLUME_UP"), eq(Map.of("delta", 5)), any(), any()))
                .thenReturn(success());

        service.execute("volume_up", Map.of("amount", 5), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("VOLUME_UP"), eq(Map.of("delta", 5)), any(), any());
    }

    @Test
    void setVolumeUsesLevelParam() {
        when(pcControlActionGateway.dispatch(eq("SET_VOLUME"), eq(Map.of("level", 42)), any(), any()))
                .thenReturn(success());

        service.execute("set_volume", Map.of("level", 42), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("SET_VOLUME"), eq(Map.of("level", 42)), any(), any());
    }

    @Test
    void openAppUsesAppParamOrDefaultBrowser() {
        when(pcControlActionGateway.dispatch(eq("OPEN_APP"), eq(Map.of("app", "browser")), any(), any()))
                .thenReturn(success());

        service.execute("open_app", Map.of(), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("OPEN_APP"), eq(Map.of("app", "browser")), any(), any());
    }

    @Test
    void openIdeUsesIdeParamOrDefaultCode() {
        when(pcControlActionGateway.dispatch(eq("OPEN_APP"), eq(Map.of("app", "webstorm")), any(), any()))
                .thenReturn(success());

        service.execute("open_ide", Map.of("ide", "webstorm"), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("OPEN_APP"), eq(Map.of("app", "webstorm")), any(), any());
    }

    @Test
    void scenarioActionsDispatchScenarioWithName() {
        when(pcControlActionGateway.dispatch(eq("SCENARIO"), eq(Map.of("name", "work")), any(), any()))
                .thenReturn(success());

        service.execute("work_mode", Map.of(), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("SCENARIO"), eq(Map.of("name", "work")), any(), any());
    }

    @Test
    void hotkeyActionsDispatchHotkeyWithKeyCombination() {
        when(pcControlActionGateway.dispatch(eq("HOTKEY"), eq(Map.of("keyCombination", "ctrl+c")), any(), any()))
                .thenReturn(success());

        service.execute("clipboard_copy", Map.of(), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("HOTKEY"), eq(Map.of("keyCombination", "ctrl+c")), any(), any());
    }

    @Test
    void systemCommandActionsDispatchSystemCommand() {
        when(pcControlActionGateway.dispatch(eq("SYSTEM_COMMAND"), eq(Map.of("command", "sleep")), any(), any()))
                .thenReturn(success());

        service.execute("sleep_mode", Map.of(), "ru", "corr-1", "user-1");

        verify(pcControlActionGateway).dispatch(eq("SYSTEM_COMMAND"), eq(Map.of("command", "sleep")), any(), any());
    }

    @Test
    void dispatchPcReturnsLocalizedFailureTextWhenExecutionNotSucceeded() {
        when(pcControlActionGateway.dispatch(eq("MUTE"), any(), any(), any())).thenReturn(failure());

        LocalIntentExecutionService.ExecutionResult result = service.execute("mute", Map.of(), "ru", "corr-1", "user-1");

        assertEquals("Не удалось выполнить команду локально.", result.responseText());
        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void dispatchPcCatchesGatewayExceptionAndReturnsFailedResult() {
        when(pcControlActionGateway.dispatch(eq("MUTE"), any(), any(), any()))
                .thenThrow(new RuntimeException("gateway down"));

        LocalIntentExecutionService.ExecutionResult result = service.execute("mute", Map.of(), "ru", "corr-1", "user-1");

        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertTrue(result.failureReason().startsWith("LOCAL_PC_FALLBACK_FAILED"));
        assertEquals("Локальное выполнение команды недоступно.", result.responseText());
    }

    @Test
    void smartHomeActionSucceeds() {
        Map<String, Object> params = Map.of("deviceId", "kitchen_light", "action", "TURN_ON", "payload", "on");

        LocalIntentExecutionService.ExecutionResult result = service.execute("smart_home_action", params, "ru", "corr-1", "user-1");

        assertTrue(result.executionSucceeded());
        assertEquals("Команда умного дома отправлена.", result.responseText());
        verify(smartHomeActionGateway).execute("user-1", "kitchen_light", "TURN_ON", "on");
    }

    @Test
    void smartHomeActionDefaultsToLocalUserWhenUserIdBlank() {
        Map<String, Object> params = Map.of("deviceId", "kitchen_light", "action", "TURN_ON");

        service.execute("smart_home_action", params, "ru", "corr-1", "");

        verify(smartHomeActionGateway).execute("local-user", "kitchen_light", "TURN_ON", null);
    }

    @Test
    void smartHomeActionFailsWithMissingParameters() {
        LocalIntentExecutionService.ExecutionResult result =
                service.execute("smart_home_action", Map.of(), "ru", "corr-1", "user-1");

        assertFalse(result.executionSucceeded());
        assertEquals("SMART_HOME_PARAMETERS_INVALID", result.failureReason());
    }

    @Test
    void smartHomeActionCatchesGatewayException() {
        Map<String, Object> params = Map.of("deviceId", "kitchen_light", "action", "TURN_ON");
        org.mockito.Mockito.doThrow(new RuntimeException("offline"))
                .when(smartHomeActionGateway).execute(any(), any(), any(), any());

        LocalIntentExecutionService.ExecutionResult result = service.execute("smart_home_action", params, "ru", "corr-1", "user-1");

        assertFalse(result.executionSucceeded());
        assertTrue(result.failureReason().startsWith("SMART_HOME_CAPABILITY_UNAVAILABLE"));
    }

    /**
     * The execute() switch has ~60 case labels that all funnel into dispatchPc()/hotkey()/
     * scenario()/systemCommand(), which all end up calling pcControlActionGateway.dispatch()
     * with a specific (pcAction, params) pair. Rather than hand-writing one @Test per label
     * (mostly-identical bodies), this table-driven test exercises every remaining case label
     * not already covered by a more detailed test above and asserts the exact dispatch call.
     */
    @ParameterizedTest(name = "[{index}] action={0} -> pcAction={1} params={2}")
    @MethodSource("remainingSwitchCases")
    void everyRemainingActionDispatchesExpectedPcActionAndParams(String action, String pcAction, Map<String, Object> params) {
        when(pcControlActionGateway.dispatch(eq(pcAction), eq(params), any(), any())).thenReturn(success());

        LocalIntentExecutionService.ExecutionResult result = service.execute(action, Map.of(), "ru", "corr-1", "user-1");

        assertTrue(result.actionResolved(), "action should resolve: " + action);
        verify(pcControlActionGateway).dispatch(eq(pcAction), eq(params), eq("user-1"), eq("corr-1"));
    }

    private static Stream<Arguments> remainingSwitchCases() {
        return Stream.of(
                Arguments.of("volume_down", "VOLUME_DOWN", Map.of("delta", 10)),
                Arguments.of("mute", "MUTE", Map.of()),
                Arguments.of("unmute", "UNMUTE", Map.of()),
                Arguments.of("play", "PLAY_PAUSE", Map.of()),
                Arguments.of("resume", "PLAY_PAUSE", Map.of()),
                Arguments.of("media_toggle", "PLAY_PAUSE", Map.of()),
                Arguments.of("pause", "PAUSE", Map.of()),
                Arguments.of("stop", "PAUSE", Map.of()),
                Arguments.of("next", "NEXT", Map.of()),
                Arguments.of("next_track", "NEXT", Map.of()),
                Arguments.of("media_next", "NEXT", Map.of()),
                Arguments.of("previous", "PREV", Map.of()),
                Arguments.of("prev", "PREV", Map.of()),
                Arguments.of("previous_track", "PREV", Map.of()),
                Arguments.of("media_prev", "PREV", Map.of()),
                Arguments.of("launch_app", "OPEN_APP", Map.of("app", "browser")),
                Arguments.of("open_code", "OPEN_APP", Map.of("app", "code")),
                Arguments.of("open_notepad", "OPEN_APP", Map.of("app", "code")),
                Arguments.of("open_terminal", "OPEN_APP", Map.of("app", "terminal")),
                Arguments.of("open_news", "OPEN_URL", Map.of("url", "https://news.google.com/")),
                Arguments.of("relax_mode", "SCENARIO", Map.of("name", "rest")),
                Arguments.of("focus_mode", "SCENARIO", Map.of("name", "focus")),
                Arguments.of("party_mode", "SCENARIO", Map.of("name", "party")),
                Arguments.of("protocol_house_party", "SCENARIO", Map.of("name", "party")),
                Arguments.of("shutdown_mode", "SCENARIO", Map.of("name", "clean_slate")),
                Arguments.of("protocol_clean_slate", "SCENARIO", Map.of("name", "clean_slate")),
                Arguments.of("protocol_cozy_evening", "SCENARIO", Map.of("name", "cozy_evening")),
                Arguments.of("protocol_guests", "SCENARIO", Map.of("name", "guests")),
                Arguments.of("protocol_holiday", "SCENARIO", Map.of("name", "holiday")),
                Arguments.of("game_mode", "SCENARIO", Map.of("name", "game")),
                Arguments.of("protocol_morning", "SCENARIO", Map.of("name", "morning")),
                Arguments.of("protocol_leaving", "SCENARIO", Map.of("name", "leaving")),
                Arguments.of("protocol_panic", "SCENARIO", Map.of("name", "panic")),
                Arguments.of("minimize_window", "MINIMIZE", Map.of()),
                Arguments.of("window_minimize", "MINIMIZE", Map.of()),
                Arguments.of("maximize_window", "MAXIMIZE", Map.of()),
                Arguments.of("window_maximize", "MAXIMIZE", Map.of()),
                Arguments.of("lock_screen", "LOCK_SCREEN", Map.of()),
                Arguments.of("clipboard_paste", "HOTKEY", Map.of("keyCombination", "ctrl+v")),
                Arguments.of("undo_action", "HOTKEY", Map.of("keyCombination", "ctrl+z")),
                Arguments.of("switch_window", "HOTKEY", Map.of("keyCombination", "Alt+Tab")),
                Arguments.of("close_window", "HOTKEY", Map.of("keyCombination", "Alt+F4")),
                Arguments.of("fullscreen", "HOTKEY", Map.of("keyCombination", "F11")),
                Arguments.of("refresh_page", "HOTKEY", Map.of("keyCombination", "F5")),
                Arguments.of("navigate_back", "HOTKEY", Map.of("keyCombination", "Alt+Left")),
                Arguments.of("navigate_forward", "HOTKEY", Map.of("keyCombination", "Alt+Right")),
                Arguments.of("show_desktop", "HOTKEY", Map.of("keyCombination", "Super+d")),
                Arguments.of("open_settings", "OPEN_APP", Map.of("app", "settings")),
                Arguments.of("system_search", "HOTKEY", Map.of("keyCombination", "Super_L")),
                Arguments.of("switch_language", "HOTKEY", Map.of("keyCombination", "Alt+Shift")),
                Arguments.of("screenshot", "HOTKEY", Map.of("keyCombination", "Print")),
                Arguments.of("monitor_off", "SYSTEM_COMMAND", Map.of("command", "monitor_off")),
                Arguments.of("network_check", "OPEN_URL", Map.of("url", "https://fast.com/")),
                Arguments.of("find_in_page", "HOTKEY", Map.of("keyCombination", "ctrl+f")),
                Arguments.of("focus_address_bar", "HOTKEY", Map.of("keyCombination", "ctrl+l")),
                Arguments.of("rename_item", "HOTKEY", Map.of("keyCombination", "F2")),
                Arguments.of("delete_selection", "HOTKEY", Map.of("keyCombination", "Delete")),
                Arguments.of("press_enter", "HOTKEY", Map.of("keyCombination", "Return")));
    }
}
