package org.jarvis.pccontrol.controller;

import org.jarvis.pccontrol.model.DesktopApplicationsResponse;
import org.jarvis.pccontrol.model.DesktopApplication;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.MouseClickRequest;
import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenFileRequest;
import org.jarvis.pccontrol.model.SendKeysRequest;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.model.WindowListResponse;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DesktopControlControllerTest {

    @Mock
    private DesktopControlService desktopControlService;

    @InjectMocks
    private DesktopControlController controller;

    @Test
    void listsDiscoveredApplications() {
        DesktopApplicationsResponse response = new DesktopApplicationsResponse(
                List.of(new DesktopApplication(
                        "firefox.desktop",
                        "Firefox",
                        List.of("Firefox", "firefox"),
                        List.of("Network", "WebBrowser"),
                        false,
                        "/usr/share/applications/firefox.desktop")),
                0);
        when(desktopControlService.listApplications()).thenReturn(response);

        ResponseEntity<DesktopApplicationsResponse> result = controller.listApplications();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(1, result.getBody().count());
        assertEquals("Firefox", result.getBody().applications().getFirst().name());
    }

    @Test
    void opensApplicationThroughDesktopService() throws IOException {
        DesktopOperationResponse operation = new DesktopOperationResponse(
                true,
                "open_app",
                "Application launch initiated",
                Map.of("desktopId", "firefox.desktop"),
                Instant.parse("2026-03-14T09:00:00Z"));
        when(desktopControlService.openApp(new OpenAppRequest("Firefox"))).thenReturn(operation);

        ResponseEntity<DesktopOperationResponse> result = controller.openApp(new OpenAppRequest("Firefox"));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("open_app", result.getBody().operation());
        assertEquals("firefox.desktop", result.getBody().details().get("desktopId"));
    }

    @Test
    void returnsVolumeState() throws IOException, InterruptedException {
        when(desktopControlService.getVolume()).thenReturn(new VolumeState(55, false, "pactl"));

        ResponseEntity<VolumeState> result = controller.getVolume();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(55, result.getBody().level());
        assertEquals("pactl", result.getBody().backend());
    }

    @Test
    void opensFileThroughDesktopService() throws IOException {
        DesktopOperationResponse operation = new DesktopOperationResponse(
                true, "open_file", "File open initiated",
                Map.of("path", "/tmp/test.txt"), null);
        when(desktopControlService.openFile(new OpenFileRequest("/tmp/test.txt"))).thenReturn(operation);

        ResponseEntity<DesktopOperationResponse> result = controller.openFile(new OpenFileRequest("/tmp/test.txt"));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("open_file", result.getBody().operation());
    }

    @Test
    void focusesWindowThroughDesktopService() throws Exception {
        WindowFocusRequest request = new WindowFocusRequest("0x04000003", null);
        DesktopOperationResponse operation = new DesktopOperationResponse(
                true, "window_focus", "Window focused",
                Map.of("backend", "wmctrl"), null);
        when(desktopControlService.focusWindow(request)).thenReturn(operation);

        ResponseEntity<DesktopOperationResponse> result = controller.focusWindow(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("window_focus", result.getBody().operation());
    }

    @Test
    void returnsActiveWindow() throws Exception {
        WindowInfo windowInfo = new WindowInfo("12345", "Terminal", "gnome-terminal", 0);
        when(desktopControlService.getActiveWindow()).thenReturn(windowInfo);

        ResponseEntity<WindowInfo> result = controller.getActiveWindow();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("Terminal", result.getBody().title());
    }

    @Test
    void returnsWindowList() throws Exception {
        WindowListResponse response = new WindowListResponse(
                List.of(new WindowInfo("1", "Terminal", "", 0)), 1);
        when(desktopControlService.listWindows()).thenReturn(response);

        ResponseEntity<WindowListResponse> result = controller.listWindows();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(1, result.getBody().count());
    }

    @Test
    void sendsKeysThroughDesktopService() throws Exception {
        SendKeysRequest request = new SendKeysRequest("ctrl+c", null);
        DesktopOperationResponse operation = new DesktopOperationResponse(
                true, "send_keys", "Keys sent",
                Map.of("keys", "ctrl+c"), null);
        when(desktopControlService.sendKeys(request)).thenReturn(operation);

        ResponseEntity<DesktopOperationResponse> result = controller.sendKeys(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("send_keys", result.getBody().operation());
    }

    @Test
    void clicksMouseThroughDesktopService() throws Exception {
        MouseClickRequest request = new MouseClickRequest(100, 200, null);
        DesktopOperationResponse operation = new DesktopOperationResponse(
                true, "mouse_click", "Mouse click performed",
                Map.of("x", 100, "y", 200, "button", 1), null);
        when(desktopControlService.mouseClick(request)).thenReturn(operation);

        ResponseEntity<DesktopOperationResponse> result = controller.mouseClick(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("mouse_click", result.getBody().operation());
    }
}
