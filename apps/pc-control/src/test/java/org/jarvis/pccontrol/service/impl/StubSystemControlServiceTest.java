package org.jarvis.pccontrol.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * StubSystemControlService performs no real OS interaction - every method only logs.
 * These tests exercise every method to confirm none of them throw and the stub
 * behaves as a safe no-op implementation for the Kubernetes/headless profile.
 */
class StubSystemControlServiceTest {

    private StubSystemControlService service;

    @BeforeEach
    void setUp() {
        service = new StubSystemControlService();
    }

    @Test
    void volumeOperationsDoNotThrow() {
        assertDoesNotThrow(() -> service.changeVolume(10, "+"));
        assertDoesNotThrow(() -> service.changeVolume(10, "-"));
        assertDoesNotThrow(() -> service.setVolume(50));
        assertDoesNotThrow(() -> service.mute());
        assertDoesNotThrow(() -> service.unmute());
    }

    @Test
    void mediaOperationsDoNotThrow() {
        assertDoesNotThrow(() -> service.playPause());
        assertDoesNotThrow(() -> service.pause());
        assertDoesNotThrow(() -> service.next());
        assertDoesNotThrow(() -> service.prev());
        assertDoesNotThrow(() -> service.beep());
    }

    @Test
    void appAndUrlOperationsDoNotThrow() {
        assertDoesNotThrow(() -> service.openApp("code"));
        assertDoesNotThrow(() -> service.openUrl("https://example.com"));
        assertDoesNotThrow(() -> service.executeHotkey("Alt+Tab"));
        assertDoesNotThrow(() -> service.typeText("hello jarvis"));
        assertDoesNotThrow(() -> service.typeText(null));
    }

    @Test
    void windowOperationsDoNotThrow() {
        assertDoesNotThrow(() -> service.focusWindow("Firefox"));
        assertDoesNotThrow(() -> service.closeWindow("Firefox"));
        assertDoesNotThrow(() -> service.minimizeWindow("Firefox"));
        assertDoesNotThrow(() -> service.maximizeWindow("Firefox"));
        assertDoesNotThrow(() -> service.normalizeWindow("Firefox"));
    }

    @Test
    void mouseOperationsDoNotThrow() {
        assertDoesNotThrow(() -> service.moveMouseAbsolute(10, 20));
        assertDoesNotThrow(() -> service.leftClick());
        assertDoesNotThrow(() -> service.rightClick());
        assertDoesNotThrow(() -> service.leftButtonDown());
        assertDoesNotThrow(() -> service.leftButtonUp());
    }

    @Test
    void systemOperationsDoNotThrow() {
        assertDoesNotThrow(() -> service.emptyTrash());
        assertDoesNotThrow(() -> service.openOpticalDrive());
        assertDoesNotThrow(() -> service.closeOpticalDrive());
        assertDoesNotThrow(() -> service.sendNotification("Title", "Message"));
        assertDoesNotThrow(() -> service.sleep());
        assertDoesNotThrow(() -> service.turnMonitorOff());
        assertDoesNotThrow(() -> service.lockScreen());
        assertDoesNotThrow(() -> service.takeScreenshot("/tmp/shot.png"));
    }
}
