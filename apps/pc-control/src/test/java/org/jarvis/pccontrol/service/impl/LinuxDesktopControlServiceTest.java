package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.model.DesktopApplication;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenFileRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.model.SendKeysRequest;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinuxDesktopControlServiceTest {

    @Mock
    private DesktopEntryApplicationCatalog applicationCatalog;

    @Mock
    private LinuxBrowserControl browserControl;

    @Mock
    private LinuxAudioControl audioControl;

    @Mock
    private LinuxSystemInfoProvider systemInfoProvider;

    @Mock
    private LinuxWindowControl windowControl;

    @Mock
    private LinuxInputControl inputControl;

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private CommandLocator commandLocator;

    private LinuxDesktopControlService desktopControlService;

    @BeforeEach
    void setUp() {
        desktopControlService = new LinuxDesktopControlService(
                applicationCatalog,
                browserControl,
                audioControl,
                systemInfoProvider,
                windowControl,
                inputControl,
                commandExecutor,
                commandLocator);
    }

    @Test
    void openAppLaunchesDiscoveredApplicationCommand() throws IOException {
        DesktopApplication firefox = new DesktopApplication(
                "firefox.desktop",
                "Firefox",
                List.of("Firefox", "firefox"),
                List.of("Network", "WebBrowser"),
                false,
                "/usr/share/applications/firefox.desktop");
        when(applicationCatalog.resolve("Firefox"))
                .thenReturn(new DesktopLaunchTarget(firefox, List.of("firefox")));

        DesktopOperationResponse response = desktopControlService.openApp(new OpenAppRequest("Firefox"));

        assertTrue(response.success());
        assertEquals("open_app", response.operation());
        assertEquals("Firefox", response.details().get("name"));
        assertEquals("firefox.desktop", response.details().get("desktopId"));
        verify(commandExecutor).start(List.of("firefox"));
    }

    @Test
    void openUrlDelegatesToBrowserControl() throws IOException {
        DesktopOperationResponse expected = new DesktopOperationResponse(
                true,
                "open_url",
                "URL opened",
                Map.of("url", "https://jarvis.local"),
                Instant.parse("2026-03-14T09:00:00Z"));
        when(browserControl.openUrl("https://jarvis.local", null)).thenReturn(expected);

        DesktopOperationResponse response = desktopControlService.openUrl(new OpenUrlRequest("https://jarvis.local", null));

        assertEquals(expected, response);
    }

    @Test
    void openFileLaunchesXdgOpenWhenAvailable() throws IOException {
        when(commandLocator.isAvailable("xdg-open")).thenReturn(true);

        DesktopOperationResponse response = desktopControlService.openFile(
                new OpenFileRequest("/home/user/document.pdf"));

        assertTrue(response.success());
        assertEquals("open_file", response.operation());
        assertEquals("/home/user/document.pdf", response.details().get("path"));
        verify(commandExecutor).start(List.of("xdg-open", "/home/user/document.pdf"));
    }

    @Test
    void openFileThrowsMissingToolWhenXdgOpenUnavailable() {
        when(commandLocator.isAvailable("xdg-open")).thenReturn(false);

        MissingToolException ex = assertThrows(MissingToolException.class, () ->
                desktopControlService.openFile(new OpenFileRequest("/home/user/document.pdf")));

        assertEquals("xdg-open", ex.getToolName());
    }

    @Test
    void openFileRejectsBlankPath() {
        assertThrows(IllegalArgumentException.class, () ->
                desktopControlService.openFile(new OpenFileRequest("")));
    }

    @Test
    void openFileRejectsNull() {
        assertThrows(IllegalArgumentException.class, () ->
                desktopControlService.openFile(null));
    }

    @Test
    void setVolumeDelegatesToAudioControl() throws IOException, InterruptedException {
        VolumeState expected = new VolumeState(35, false, "wpctl");
        when(audioControl.setVolume(35)).thenReturn(expected);

        VolumeState response = desktopControlService.setVolume(35);

        assertEquals(expected, response);
    }

    @Test
    void focusWindowDelegatesToWindowControl() throws Exception {
        WindowFocusRequest request = new WindowFocusRequest("0x04000003", null);
        DesktopOperationResponse expected = new DesktopOperationResponse(
                true, "window_focus", "Window focused",
                Map.of("backend", "wmctrl", "target", "0x04000003"), null);
        when(windowControl.focusWindow(request)).thenReturn(expected);

        DesktopOperationResponse response = desktopControlService.focusWindow(request);

        assertEquals(expected, response);
        verify(windowControl).focusWindow(request);
    }

    @Test
    void getActiveWindowDelegatesToWindowControl() throws Exception {
        WindowInfo expected = new WindowInfo("12345", "Terminal", "gnome-terminal", 0);
        when(windowControl.getActiveWindow()).thenReturn(expected);

        WindowInfo response = desktopControlService.getActiveWindow();

        assertEquals(expected, response);
    }

    @Test
    void sendKeysDelegatesToInputControl() throws Exception {
        SendKeysRequest request = new SendKeysRequest("ctrl+c", null);
        DesktopOperationResponse expected = new DesktopOperationResponse(
                true, "send_keys", "Keys sent",
                Map.of("keys", "ctrl+c"), null);
        when(inputControl.sendKeys(request)).thenReturn(expected);

        DesktopOperationResponse response = desktopControlService.sendKeys(request);

        assertEquals(expected, response);
        verify(inputControl).sendKeys(request);
    }
}
