package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * LinuxSystemControlService mostly wraps ProcessBuilder calls to real OS utilities
 * (xdotool, wmctrl, playerctl, notify-send, ...) with no injectable command executor,
 * so those code paths cannot be meaningfully unit tested without a real desktop/X11
 * session (see risks in the coverage report). These tests cover the parts of the
 * class that are pure delegation/validation logic, reachable via mocking the
 * injected LinuxAudioControl and DesktopControlService collaborators.
 */
@ExtendWith(MockitoExtension.class)
class LinuxSystemControlServiceTest {

    @Mock
    private LinuxAudioControl audioControl;

    @Mock
    private DesktopControlService desktopControlService;

    private LinuxSystemControlService service;

    @BeforeEach
    void setUp() {
        service = new LinuxSystemControlService(audioControl, desktopControlService);
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
    void beepNeverThrowsEvenWhenSoundToolsAreMissing() {
        // beep() swallows IOException/InterruptedException internally and falls back
        // to a terminal bell, so it must never propagate an exception to the caller.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.beep());
    }
}
