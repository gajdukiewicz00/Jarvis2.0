package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;
import org.jarvis.pccontrol.model.PcScenarioDefinition;
import org.jarvis.pccontrol.model.PcScenarioStep;
import org.jarvis.pccontrol.security.CommandValidator;
import org.jarvis.pccontrol.service.impl.DefaultPcActionExecutionService;
import org.jarvis.pccontrol.service.impl.InMemoryPcScenarioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PcActionExecutionServiceTest {

    @Mock
    private SystemControlService systemControlService;

    @Mock
    private TimerSchedulerService timerSchedulerService;

    private CommandValidator commandValidator;

    private DefaultPcActionExecutionService service;

    @BeforeEach
    void setUp() {
        commandValidator = new CommandValidator();
        ReflectionTestUtils.setField(commandValidator, "allowedActions", List.of(
                "MEDIA_CONTROL",
                "VOLUME_UP",
                "VOLUME_DOWN",
                "SET_VOLUME",
                "VOLUME_SET",
                "MUTE",
                "UNMUTE",
                "PLAY_PAUSE",
                "PAUSE",
                "NEXT",
                "PREV",
                "OPEN_APP",
                "OPEN_URL",
                "HOTKEY",
                "NOTIFY",
                "SCREENSHOT",
                "LOCK_SCREEN",
                "SYSTEM_COMMAND",
                "SCENARIO",
                "FAKE_ACTION"
        ));
        service = new DefaultPcActionExecutionService(
                systemControlService,
                timerSchedulerService,
                commandValidator,
                new InMemoryPcScenarioRegistry());
    }

    // --- Top-level validation / routing ---

    @Test
    void executeRejectsNullActionType() {
        PcActionResult result = service.execute(new PcActionRequest(null, Map.of()));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.REJECTED, result.status());
        assertEquals("INVALID_ACTION_TYPE", result.errorCode());
    }

    @Test
    void executeRejectsBlankActionType() {
        PcActionResult result = service.execute(new PcActionRequest("   ", Map.of()));

        assertFalse(result.success());
        assertEquals("INVALID_ACTION_TYPE", result.errorCode());
    }

    @Test
    void executeBlocksActionNotInAllowList() {
        PcActionResult result = service.execute(new PcActionRequest("SHUTDOWN", Map.of()));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.REJECTED, result.status());
        assertEquals("BLOCKED_ACTION", result.errorCode());
        assertTrue(result.message().contains("SHUTDOWN"));
    }

    @Test
    void executeReturnsUnknownActionTypeForAllowedButUnhandledAction() {
        PcActionResult result = service.execute(new PcActionRequest("FAKE_ACTION", Map.of()));

        assertFalse(result.success());
        assertEquals("UNKNOWN_ACTION_TYPE", result.errorCode());
    }

    // --- MEDIA_CONTROL ---

    @Test
    void mediaControlWithActionParameterDelegatesToPause() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("MEDIA_CONTROL", Map.of("action", "pause")));

        assertTrue(result.success());
        assertEquals("PAUSE", result.actionType());
        verify(systemControlService).pause();
    }

    @Test
    void mediaControlWithMediaActionParameterDelegatesToNext() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("MEDIA_CONTROL", Map.of("mediaAction", "next")));

        assertTrue(result.success());
        assertEquals("NEXT", result.actionType());
        verify(systemControlService).next();
    }

    @Test
    void mediaControlWithoutActionUsesDefaultDeltaAndPlusDirection() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("MEDIA_CONTROL", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).changeVolume(5, "+");
    }

    @Test
    void mediaControlWithExplicitMinusDirectionAndCustomDelta() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("MEDIA_CONTROL",
                Map.of("deltaPercent", "20", "direction", "-")));

        assertTrue(result.success());
        verify(systemControlService).changeVolume(20, "-");
    }

    @Test
    void mediaControlWithNonNumericDeltaPropagatesIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.execute(new PcActionRequest("MEDIA_CONTROL", Map.of("deltaPercent", "abc"))));
    }

    // --- Volume primitives ---

    @Test
    void volumeUpWithInvalidDeltaPropagatesIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.execute(new PcActionRequest("VOLUME_UP", Map.of("delta", "not-a-number"))));
    }

    @Test
    void volumeUpWithOutOfRangeDeltaPropagatesIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.execute(new PcActionRequest("VOLUME_UP", Map.of("delta", "500"))));
    }

    @Test
    void volumeDownReturnsStructuredResult() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("VOLUME_DOWN", Map.of("delta", "8")));

        assertTrue(result.success());
        assertEquals(8, result.details().get("delta"));
        assertEquals("-", result.details().get("direction"));
        verify(systemControlService).changeVolume(8, "-");
    }

    @Test
    void setVolumeReturnsStructuredResult() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SET_VOLUME", Map.of("level", "77")));

        assertTrue(result.success());
        assertEquals("SET_VOLUME", result.actionType());
        assertEquals(77, result.details().get("level"));
        verify(systemControlService).setVolume(77);
    }

    @Test
    void volumeSetAliasResolvesToSetVolumeActionType() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("VOLUME_SET", Map.of("level", "10")));

        assertEquals("SET_VOLUME", result.actionType());
        verify(systemControlService).setVolume(10);
    }

    @Test
    void muteReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("MUTE", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).mute();
    }

    @Test
    void unmuteReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("UNMUTE", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).unmute();
    }

    @Test
    void playPauseReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("PLAY_PAUSE", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).playPause();
    }

    @Test
    void nextReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("NEXT", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).next();
    }

    @Test
    void prevReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("PREV", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).prev();
    }

    // --- OPEN_APP / OPEN_URL ---

    @Test
    void openAppReturnsSuccessWhenAppProvided() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("OPEN_APP", Map.of("app", "code")));

        assertTrue(result.success());
        verify(systemControlService).openApp("code");
    }

    @Test
    void openAppUsesAppNameAliasWhenAppMissing() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("OPEN_APP", Map.of("appName", "spotify")));

        assertTrue(result.success());
        verify(systemControlService).openApp("spotify");
    }

    @Test
    void openAppRejectedWhenAppParameterMissing() {
        PcActionResult result = service.execute(new PcActionRequest("OPEN_APP", Map.of()));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.REJECTED, result.status());
        assertNull(result.errorCode());
        assertTrue(result.message().contains("app"));
    }

    @Test
    void openAppTreatsIllegalArgumentExceptionFromServiceAsRejection() throws Exception {
        doThrow(new IllegalArgumentException("unsupported app")).when(systemControlService).openApp("weird");

        PcActionResult result = service.execute(new PcActionRequest("OPEN_APP", Map.of("app", "weird")));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.REJECTED, result.status());
        assertEquals("unsupported app", result.message());
    }

    @Test
    void openUrlReturnsSuccessWhenUrlProvided() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("OPEN_URL", Map.of("url", "https://example.com")));

        assertTrue(result.success());
        verify(systemControlService).openUrl("https://example.com");
    }

    @Test
    void openUrlRejectedWhenUrlMissing() {
        PcActionResult result = service.execute(new PcActionRequest("OPEN_URL", Map.of()));

        assertFalse(result.success());
        assertTrue(result.message().contains("url"));
    }

    // --- HOTKEY ---

    @Test
    void hotkeyReturnsSuccessWithKeyCombinationParameter() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("HOTKEY", Map.of("keyCombination", "Alt+Tab")));

        assertTrue(result.success());
        verify(systemControlService).executeHotkey("Alt+Tab");
    }

    @Test
    void hotkeyAcceptsKeysAlias() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("HOTKEY", Map.of("keys", "Control+c")));

        assertTrue(result.success());
        verify(systemControlService).executeHotkey("Control+c");
    }

    @Test
    void hotkeyAcceptsCombinationAlias() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("HOTKEY", Map.of("combination", "Super+d")));

        assertTrue(result.success());
        verify(systemControlService).executeHotkey("Super+d");
    }

    @Test
    void hotkeyRejectedWhenNoKeyCombinationProvided() {
        PcActionResult result = service.execute(new PcActionRequest("HOTKEY", Map.of()));

        assertFalse(result.success());
        assertTrue(result.message().contains("keyCombination"));
    }

    // --- NOTIFY / SCREENSHOT / LOCK_SCREEN ---

    @Test
    void notifyUsesDefaultTitleAndMessageWhenMissing() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("NOTIFY", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).sendNotification("Jarvis", "Notification");
    }

    @Test
    void notifyUsesProvidedTitleAndMessage() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("NOTIFY",
                Map.of("title", "Reminder", "message", "Stand up and stretch")));

        assertTrue(result.success());
        verify(systemControlService).sendNotification("Reminder", "Stand up and stretch");
    }

    @Test
    void screenshotUsesDefaultPathWhenMissing() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCREENSHOT", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).takeScreenshot("/tmp/jarvis-screenshot.png");
    }

    @Test
    void screenshotUsesProvidedPath() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCREENSHOT", Map.of("path", "/tmp/custom.png")));

        assertTrue(result.success());
        verify(systemControlService).takeScreenshot("/tmp/custom.png");
    }

    @Test
    void lockScreenReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("LOCK_SCREEN", Map.of()));

        assertTrue(result.success());
        verify(systemControlService).lockScreen();
    }

    // --- SYSTEM_COMMAND ---

    @Test
    void systemCommandRejectedWhenCommandParameterMissing() {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of()));

        assertFalse(result.success());
        assertEquals("INVALID_PARAMETER", result.errorCode());
    }

    @Test
    void systemCommandRejectedForUnknownCommand() {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of("command", "levitate")));

        assertFalse(result.success());
        assertEquals("UNKNOWN_COMMAND", result.errorCode());
    }

    @Test
    void systemCommandSleepReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of("command", "sleep")));

        assertTrue(result.success());
        verify(systemControlService).sleep();
    }

    @Test
    void systemCommandMonitorOffReturnsSuccess() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of("command", "monitor_off")));

        assertTrue(result.success());
        verify(systemControlService).turnMonitorOff();
    }

    @Test
    void systemCommandTimerRejectedWhenDurationMissing() {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of("command", "timer")));

        assertFalse(result.success());
        assertEquals("INVALID_PARAMETER", result.errorCode());
    }

    @Test
    void systemCommandTimerRejectedWhenDurationHasNoDigits() {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "soon")));

        assertFalse(result.success());
        assertEquals("INVALID_PARAMETER", result.errorCode());
    }

    @Test
    void systemCommandTimerPropagatesIllegalArgumentExceptionWhenOutOfRange() {
        assertThrows(IllegalArgumentException.class, () ->
                service.execute(new PcActionRequest("SYSTEM_COMMAND",
                        Map.of("command", "timer", "args", "999999999"))));
    }

    @Test
    void systemCommandTimerRejectedWhenLimitExceeded() {
        when(timerSchedulerService.scheduleTimer(eq(15), any(Runnable.class)))
                .thenThrow(new TimerLimitExceededException("Too many active timers (2)"));

        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "15")));

        assertFalse(result.success());
        assertEquals("TIMER_LIMIT_EXCEEDED", result.errorCode());
    }

    @Test
    void systemCommandTimerSchedulesAndCallbackNotifiesAndBeeps() throws Exception {
        when(timerSchedulerService.scheduleTimer(eq(15), any(Runnable.class))).thenReturn("timer-42");

        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "15")));

        assertTrue(result.success());
        assertEquals("timer-42", result.details().get("timerId"));

        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(timerSchedulerService).scheduleTimer(eq(15), callbackCaptor.capture());
        callbackCaptor.getValue().run();

        verify(systemControlService).sendNotification("Timer", "Time is up!");
        verify(systemControlService).beep();
    }

    @Test
    void timerCallbackSwallowsIOExceptionFromNotificationAndSkipsBeep() throws Exception {
        when(timerSchedulerService.scheduleTimer(eq(5), any(Runnable.class))).thenReturn("t1");
        doThrow(new IOException("boom")).when(systemControlService).sendNotification("Timer", "Time is up!");

        service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of("command", "timer", "args", "5")));

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(timerSchedulerService).scheduleTimer(eq(5), captor.capture());

        assertDoesNotThrow(() -> captor.getValue().run());
        verify(systemControlService, never()).beep();
    }

    @Test
    void timerCallbackSwallowsInterruptedExceptionFromNotification() throws Exception {
        when(timerSchedulerService.scheduleTimer(eq(5), any(Runnable.class))).thenReturn("t1");
        doThrow(new InterruptedException("interrupted")).when(systemControlService).sendNotification("Timer", "Time is up!");

        service.execute(new PcActionRequest("SYSTEM_COMMAND", Map.of("command", "timer", "args", "5")));

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(timerSchedulerService).scheduleTimer(eq(5), captor.capture());

        assertDoesNotThrow(() -> captor.getValue().run());
        assertTrue(Thread.interrupted(), "interrupt flag should have been set by the callback");
        verify(systemControlService, never()).beep();
    }

    @Test
    void systemCommandCancelTimerRejectedWhenTimerIdMissing() {
        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND",
                Map.of("command", "cancel_timer")));

        assertFalse(result.success());
        assertEquals("INVALID_PARAMETER", result.errorCode());
    }

    @Test
    void systemCommandCancelTimerRejectedWhenTimerNotFound() {
        when(timerSchedulerService.cancelTimer("timer-1")).thenReturn(false);

        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND",
                Map.of("command", "cancel_timer", "timerId", "timer-1")));

        assertFalse(result.success());
        assertEquals("TIMER_NOT_FOUND", result.errorCode());
    }

    @Test
    void systemCommandCancelTimerReturnsSuccessWhenCancelled() {
        when(timerSchedulerService.cancelTimer("timer-1")).thenReturn(true);

        PcActionResult result = service.execute(new PcActionRequest("SYSTEM_COMMAND",
                Map.of("command", "cancel_timer", "timerId", "timer-1")));

        assertTrue(result.success());
        assertEquals("timer-1", result.details().get("timerId"));
    }

    // --- SCENARIO ---

    @Test
    void executeRejectsScenarioWithoutName() {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of()));

        assertFalse(result.success());
        assertEquals("INVALID_PARAMETER", result.errorCode());
    }

    @Test
    void executeScenarioReturnsStepResultsAndPartialSuccessWhenOneStepFails() throws Exception {
        doThrow(new IOException("code missing")).when(systemControlService).openApp("code");

        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "work")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.PARTIAL_SUCCESS, result.status());
        assertEquals("SCENARIO", result.actionType());
        assertEquals("work", result.details().get("scenario"));
        assertEquals(3, result.steps().size());
        assertEquals(PcActionExecutionStatus.FAILED, result.steps().get(0).status());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.steps().get(1).status());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.steps().get(2).status());

        verify(systemControlService).openApp("browser");
        verify(systemControlService).sendNotification("Work Mode", "Work scenario activated");
    }

    @Test
    void executeRejectsUnknownScenario() {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "unknown")));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.REJECTED, result.status());
        assertEquals("UNKNOWN_SCENARIO", result.errorCode());
    }

    @Test
    void executeLegacyBrowserScenarioUsesWindowAndMouseSteps() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_browser_maximize")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.status());

        InOrder inOrder = inOrder(systemControlService);
        inOrder.verify(systemControlService).maximizeWindow("Opera");
        inOrder.verify(systemControlService).leftClick();
    }

    @Test
    void executeLegacyDrawingScenarioUsesMouseDragSteps() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_draw_circle")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.status());

        InOrder inOrder = inOrder(systemControlService);
        inOrder.verify(systemControlService).moveMouseAbsolute(531, 64);
        inOrder.verify(systemControlService).leftClick();
        inOrder.verify(systemControlService).moveMouseAbsolute(158, 213);
        inOrder.verify(systemControlService).leftButtonDown();
        inOrder.verify(systemControlService).moveMouseAbsolute(447, 473);
        inOrder.verify(systemControlService).leftButtonUp();
    }

    @Test
    void executeLegacyWindowNormalizeScenario() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_window_normalize")));

        assertTrue(result.success());
        verify(systemControlService).normalizeWindow(null);
    }

    @Test
    void executeLegacyWindowMinimizeScenario() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_window_minimize")));

        assertTrue(result.success());
        verify(systemControlService).minimizeWindow(null);
    }

    @Test
    void executeLegacyEmptyTrashScenario() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_empty_trash")));

        assertTrue(result.success());
        verify(systemControlService).emptyTrash();
    }

    @Test
    void executeLegacyOpticalDriveScenarios() throws Exception {
        PcActionResult openResult = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_open_optical_drive")));
        PcActionResult closeResult = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_close_optical_drive")));

        assertTrue(openResult.success());
        assertTrue(closeResult.success());
        verify(systemControlService).openOpticalDrive();
        verify(systemControlService).closeOpticalDrive();
    }

    @Test
    void executeLegacyCleanSlateCancelScenarioUsesRightClickAndMultipleMoves() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_clean_slate_cancel")));

        assertTrue(result.success());
        verify(systemControlService).rightClick();
        verify(systemControlService, atLeastOnce()).moveMouseAbsolute(1026, 25);
    }

    @Test
    void executeLegacyYandexLikeScenarioUsesWindowFocusWithTitle() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_yandex_like")));

        assertTrue(result.success());
        verify(systemControlService).focusWindow("[Яя]ндекс [Мм]узыка");
        verify(systemControlService).executeHotkey("f");
        verify(systemControlService).minimizeWindow("[Яя]ндекс [Мм]узыка");
    }

    @Test
    void executeScenarioReturnsFailedStatusWhenAllStepsFail() throws Exception {
        doThrow(new IOException("no audio")).when(systemControlService).mute();
        doThrow(new IOException("no display")).when(systemControlService).executeHotkey("Super+d");
        doThrow(new IOException("no notify daemon")).when(systemControlService).sendNotification("Panic", "Panic protocol activated");

        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "panic")));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.FAILED, result.status());
        assertEquals(3L, result.details().get("failedSteps"));
        assertEquals(0L, result.details().get("successfulSteps"));
    }

    @Test
    void scenarioHandlesWindowCloseAndUnsupportedStepWhileAggregatingSuccess() {
        PcScenarioRegistry customRegistry = mock(PcScenarioRegistry.class);
        PcScenarioDefinition definition = new PcScenarioDefinition(
                "custom_close",
                "Closes a window and attempts an unsupported step.",
                List.of(
                        new PcScenarioStep("close-window", "WINDOW_CLOSE", Map.of(), "Close active window"),
                        new PcScenarioStep("unsupported", "SOMETHING_UNSUPPORTED", Map.of(), "Not a real action")));
        when(customRegistry.findByName("custom_close")).thenReturn(Optional.of(definition));

        DefaultPcActionExecutionService customService = new DefaultPcActionExecutionService(
                systemControlService, timerSchedulerService, commandValidator, customRegistry);

        PcActionResult result = customService.execute(new PcActionRequest("SCENARIO", Map.of("name", "custom_close")));

        // WINDOW_CLOSE succeeds and the rejected/unsupported step is not counted as a
        // FAILED step, so the scenario is (surprisingly) reported as an overall SUCCESS.
        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.steps().get(0).status());
        assertEquals(PcActionExecutionStatus.REJECTED, result.steps().get(1).status());
        assertTrue(result.steps().get(1).message().contains("not supported inside scenario"));
    }

    @Test
    void scenarioStepWithNonNumericMouseCoordinatePropagatesIllegalArgumentException() {
        PcScenarioRegistry customRegistry = mock(PcScenarioRegistry.class);
        PcScenarioDefinition definition = new PcScenarioDefinition(
                "bad_mouse_move",
                "Scenario with a missing mouse coordinate.",
                List.of(new PcScenarioStep("move", "MOUSE_MOVE", Map.of(), "Missing coordinates")));
        when(customRegistry.findByName("bad_mouse_move")).thenReturn(Optional.of(definition));

        DefaultPcActionExecutionService customService = new DefaultPcActionExecutionService(
                systemControlService, timerSchedulerService, commandValidator, customRegistry);

        assertThrows(IllegalArgumentException.class, () ->
                customService.execute(new PcActionRequest("SCENARIO", Map.of("name", "bad_mouse_move"))));
    }

    @Test
    void executeVolumeUpReturnsStructuredResult() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("VOLUME_UP", Map.of("delta", "15")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.status());
        assertEquals(15, result.details().get("delta"));
        assertEquals("+", result.details().get("direction"));
    }
}
