package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandResult;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LinuxSystemControlService routes every external command through the injected
 * CommandExecutor, so its exit-code-to-outcome mapping, fallback logic, and
 * screenshot path confinement can be unit tested by mocking that collaborator -
 * no real desktop/X11 session is required.
 */
@ExtendWith(MockitoExtension.class)
class LinuxSystemControlServiceTest {

    private static final String SCREENSHOT_DIR = "/tmp";

    @Mock
    private LinuxAudioControl audioControl;

    @Mock
    private DesktopControlService desktopControlService;

    @Mock
    private CommandExecutor commandExecutor;

    private LinuxSystemControlService service;

    @BeforeEach
    void setUp() throws Exception {
        // Most tests below don't care about the exact command executed; give every
        // unstubbed invocation a benign success result so unrelated collaborators
        // (e.g. beep()'s internal execWithFallback call) don't NPE on a bare mock.
        lenient().when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(0, "", ""));
        service = new LinuxSystemControlService(audioControl, desktopControlService, commandExecutor, SCREENSHOT_DIR);
    }

    @Test
    void changeVolumeDelegatesToAudioControl() throws Exception {
        service.changeVolume(15, "+");

        verify(audioControl).changeVolume(15, "+");
    }

    @Test
    void setVolumeClampsAboveUpperBoundBeforeDelegating() throws Exception {
        service.setVolume(500);

        verify(audioControl).setVolume(100);
    }

    @Test
    void setVolumeClampsBelowLowerBoundBeforeDelegating() throws Exception {
        service.setVolume(-20);

        verify(audioControl).setVolume(0);
    }

    @Test
    void muteDelegatesToAudioControl() throws Exception {
        service.mute();

        verify(audioControl).mute();
    }

    @Test
    void unmuteDelegatesToAudioControl() throws Exception {
        service.unmute();

        verify(audioControl).unmute();
    }

    @Test
    void openAppDelegatesToDesktopControlService() throws Exception {
        service.openApp("code");

        verify(desktopControlService).openApp(new OpenAppRequest("code"));
    }

    @Test
    void openAppRethrowsOriginalExceptionWhenNoFallbackCommandKnown() throws Exception {
        doThrow(new IllegalArgumentException("not installed"))
                .when(desktopControlService).openApp(new OpenAppRequest("some-unknown-app"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.openApp("some-unknown-app"));
        assertEquals("not installed", thrown.getMessage());
    }

    @Test
    void openAppRequiresNonBlankAppNameWhenFallingBack() throws Exception {
        doThrow(new IllegalArgumentException("blank name not allowed"))
                .when(desktopControlService).openApp(new OpenAppRequest(""));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.openApp(""));
        assertEquals("App name is required", thrown.getMessage());
    }

    @Test
    void openUrlDelegatesToDesktopControlService() throws Exception {
        service.openUrl("https://example.com");

        verify(desktopControlService).openUrl(new OpenUrlRequest("https://example.com", null));
    }

    @Test
    void executeHotkeyRejectsBlankKeyCombination() {
        assertThrows(IllegalArgumentException.class, () -> service.executeHotkey(""));
    }

    @Test
    void executeHotkeyRejectsInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> service.executeHotkey("Alt+Tab; rm -rf /"));
    }

    @Test
    void typeTextRejectsNullText() {
        assertThrows(IllegalArgumentException.class, () -> service.typeText(null));
    }

    @Test
    void typeTextRejectsEmptyText() {
        assertThrows(IllegalArgumentException.class, () -> service.typeText(""));
    }

    @Test
    void typeTextRejectsTextExceedingMaxLength() {
        String tooLong = "a".repeat(501);
        assertThrows(IllegalArgumentException.class, () -> service.typeText(tooLong));
    }

    @Test
    void typeTextRejectsTextWithControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.typeText("hello\nworld"));
    }

    @Test
    void beepNeverThrowsEvenWhenSoundToolsAreMissing() throws Exception {
        // beep() swallows IOException/InterruptedException internally and falls back
        // to a terminal bell, so it must never propagate an exception to the caller,
        // even when the underlying command genuinely fails (non-zero exit).
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "no such device", ""));

        assertDoesNotThrow(() -> service.beep());
    }

    // --- Non-zero exit code must be a FAILURE, not "warning + success" ---

    @Test
    void sendNotificationThrowsWhenNotifySendExitsNonZero() throws Exception {
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "no notification daemon", ""));

        assertThrows(java.io.IOException.class, () -> service.sendNotification("Title", "Body"));
    }

    @Test
    void sendNotificationSucceedsAndInvokesNotifySendWhenExitCodeIsZero() throws Exception {
        service.sendNotification("Title", "Body");

        verify(commandExecutor).execute(List.of("notify-send", "Title", "Body"));
    }

    @Test
    void executeHotkeyThrowsWhenXdotoolExitsNonZero() throws Exception {
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "no display", ""));

        assertThrows(java.io.IOException.class, () -> service.executeHotkey("Alt+Tab"));
    }

    @Test
    void lockScreenThrowsWhenLoginctlExitsNonZero() throws Exception {
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "permission denied", ""));

        assertThrows(java.io.IOException.class, () -> service.lockScreen());
    }

    @Test
    void playPauseThrowsWhenPlayerctlFailsForAReasonOtherThanNoPlayers() throws Exception {
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "some other playerctl error", ""));

        assertThrows(java.io.IOException.class, () -> service.playPause());
    }

    @Test
    void playPauseDoesNotThrowWhenPlayerctlReportsNoPlayersFound() throws Exception {
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "No players found", ""));

        assertDoesNotThrow(() -> service.playPause());
    }

    // --- Screenshot path confinement ---

    @Test
    void takeScreenshotRejectsRelativeTraversalOutsideConfinedDirectory() {
        assertThrows(IllegalArgumentException.class, () -> service.takeScreenshot("../../etc/passwd"));
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void takeScreenshotRejectsAbsolutePathOutsideConfinedDirectory() {
        assertThrows(IllegalArgumentException.class, () -> service.takeScreenshot("/etc/passwd"));
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void takeScreenshotAllowsRelativePathWithinConfinedDirectory() throws Exception {
        service.takeScreenshot("shot.png");

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandExecutor).execute(captor.capture());
        assertEquals(List.of("gnome-screenshot", "-f", SCREENSHOT_DIR + "/shot.png"), captor.getValue());
    }

    @Test
    void takeScreenshotUsesDefaultFilenameWhenPathIsBlank() throws Exception {
        service.takeScreenshot("");

        verify(commandExecutor).execute(List.of("gnome-screenshot", "-f", SCREENSHOT_DIR + "/jarvis-screenshot.png"));
    }

    @Test
    void takeScreenshotThrowsWhenGnomeScreenshotExitsNonZero() throws Exception {
        when(commandExecutor.execute(anyList())).thenReturn(new CommandResult(1, "no display", ""));

        assertThrows(java.io.IOException.class, () -> service.takeScreenshot("shot.png"));
    }

    // --- openApp fallback still goes through the injectable executor ---

    @Test
    void openAppFallbackLaunchesViaCommandExecutorStart() throws Exception {
        doThrow(new IllegalArgumentException("not in catalog"))
                .when(desktopControlService).openApp(new OpenAppRequest("spotify"));

        service.openApp("spotify");

        verify(commandExecutor).start(List.of("spotify"));
    }

    @Test
    void openAppRethrowsWhenNoFallbackAndNeverTouchesCommandExecutor() throws Exception {
        doThrow(new IllegalArgumentException("not installed"))
                .when(desktopControlService).openApp(new OpenAppRequest("some-unknown-app"));

        assertThrows(IllegalArgumentException.class, () -> service.openApp("some-unknown-app"));
        verifyNoInteractions(commandExecutor);
    }
}
