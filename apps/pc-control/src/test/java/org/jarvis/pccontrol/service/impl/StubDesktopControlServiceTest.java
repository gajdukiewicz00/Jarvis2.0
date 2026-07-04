package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.DesktopApplicationsResponse;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.jarvis.pccontrol.model.MouseClickRequest;
import org.jarvis.pccontrol.model.MouseMoveRequest;
import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenFileRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.model.ScrollRequest;
import org.jarvis.pccontrol.model.SendKeysRequest;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.model.WindowListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubDesktopControlServiceTest {

    private StubDesktopControlService service;

    @BeforeEach
    void setUp() {
        service = new StubDesktopControlService();
    }

    @Test
    void listApplicationsReturnsEmptyResponse() {
        DesktopApplicationsResponse response = service.listApplications();

        assertTrue(response.applications().isEmpty());
    }

    @Test
    void openAppEchoesRequestedAppName() {
        DesktopOperationResponse response = service.openApp(new OpenAppRequest("code"));

        assertTrue(response.success());
        assertEquals("open_app", response.operation());
        assertEquals("code", response.details().get("appName"));
    }

    @Test
    void openAppRejectsNullRequestBecauseMapOfDisallowsNullValues() {
        // Map.of("appName", null) throws NPE - this documents the real (if surprising) behavior.
        assertThrows(NullPointerException.class, () -> service.openApp(null));
    }

    @Test
    void openUrlEchoesUrlAndBrowser() {
        DesktopOperationResponse response = service.openUrl(new OpenUrlRequest("https://example.com", "firefox"));

        assertTrue(response.success());
        assertEquals("open_url", response.operation());
        assertEquals("https://example.com", response.details().get("url"));
        assertEquals("firefox", response.details().get("browser"));
    }

    @Test
    void openUrlRejectsNullRequestBecauseMapOfDisallowsNullValues() {
        // Map.of("url", null, "browser", null) throws NPE - documents the real behavior.
        assertThrows(NullPointerException.class, () -> service.openUrl(null));
    }

    @Test
    void openFileEchoesPath() {
        DesktopOperationResponse response = service.openFile(new OpenFileRequest("/tmp/report.txt"));

        assertTrue(response.success());
        assertEquals("open_file", response.operation());
        assertEquals("/tmp/report.txt", response.details().get("path"));
    }

    @Test
    void openFileHandlesNullRequest() {
        DesktopOperationResponse response = service.openFile(null);

        assertEquals("", response.details().get("path"));
    }

    @Test
    void getSystemInfoReturnsHeadlessStubInfo() {
        DesktopSystemInfo info = service.getSystemInfo();

        assertEquals("linux", info.platform());
        assertEquals("stub", info.distribution());
        assertEquals("headless", info.displayServer());
        assertTrue(info.installedBrowsers().isEmpty());
    }

    @Test
    void getVolumeReturnsZeroUnmutedStub() {
        VolumeState state = service.getVolume();

        assertEquals(0, state.level());
        assertFalse(state.muted());
        assertEquals("stub", state.backend());
    }

    @Test
    void setVolumeClampsToUpperBound() {
        VolumeState state = service.setVolume(150);

        assertEquals(100, state.level());
    }

    @Test
    void setVolumeClampsToLowerBound() {
        VolumeState state = service.setVolume(-5);

        assertEquals(0, state.level());
    }

    @Test
    void setVolumeKeepsInRangeValue() {
        VolumeState state = service.setVolume(42);

        assertEquals(42, state.level());
    }

    @Test
    void focusWindowUsesWindowIdWhenPresent() {
        DesktopOperationResponse response = service.focusWindow(new WindowFocusRequest("42", "Ignored"));

        assertEquals("42", response.details().get("target"));
    }

    @Test
    void focusWindowFallsBackToWindowNameWhenIdMissing() {
        DesktopOperationResponse response = service.focusWindow(new WindowFocusRequest(null, "My Window"));

        assertEquals("My Window", response.details().get("target"));
    }

    @Test
    void focusWindowHandlesNullRequest() {
        DesktopOperationResponse response = service.focusWindow(null);

        assertEquals("", response.details().get("target"));
    }

    @Test
    void getActiveWindowReturnsStubWindow() {
        WindowInfo window = service.getActiveWindow();

        assertEquals("Stub Window", window.title());
    }

    @Test
    void listWindowsReturnsEmptyResponse() {
        WindowListResponse response = service.listWindows();

        assertTrue(response.windows().isEmpty());
        assertEquals(0, response.count());
    }

    @Test
    void sendKeysEchoesKeys() {
        DesktopOperationResponse response = service.sendKeys(new SendKeysRequest("ctrl+c", "1"));

        assertEquals("ctrl+c", response.details().get("keys"));
    }

    @Test
    void sendKeysHandlesNullRequest() {
        DesktopOperationResponse response = service.sendKeys(null);

        assertEquals("", response.details().get("keys"));
    }

    @Test
    void mouseClickEchoesCoordinatesAndButton() {
        DesktopOperationResponse response = service.mouseClick(new MouseClickRequest(10, 20, 3));

        assertEquals(10, response.details().get("x"));
        assertEquals(20, response.details().get("y"));
        assertEquals(3, response.details().get("button"));
    }

    @Test
    void mouseClickHandlesNullRequest() {
        DesktopOperationResponse response = service.mouseClick(null);

        assertEquals(0, response.details().get("x"));
        assertEquals(0, response.details().get("y"));
        assertEquals(1, response.details().get("button"));
    }

    @Test
    void mouseMoveEchoesCoordinates() {
        DesktopOperationResponse response = service.mouseMove(new MouseMoveRequest(5, 6));

        assertEquals(5, response.details().get("x"));
        assertEquals(6, response.details().get("y"));
    }

    @Test
    void mouseMoveHandlesNullRequest() {
        DesktopOperationResponse response = service.mouseMove(null);

        assertEquals(0, response.details().get("x"));
        assertEquals(0, response.details().get("y"));
    }

    @Test
    void scrollEchoesDirectionAndAmount() {
        DesktopOperationResponse response = service.scroll(new ScrollRequest(3, "up"));

        assertEquals("up", response.details().get("direction"));
        assertEquals(3, response.details().get("amount"));
    }

    @Test
    void scrollHandlesNullRequest() {
        DesktopOperationResponse response = service.scroll(null);

        assertNotNull(response);
        assertEquals("down", response.details().get("direction"));
        assertEquals(0, response.details().get("amount"));
    }
}
