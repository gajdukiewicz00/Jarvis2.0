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
import org.jarvis.pccontrol.service.DesktopControlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Primary
@ConditionalOnProperty(name = "pc-control.stub-mode", havingValue = "true")
public class StubDesktopControlService implements DesktopControlService {

    @Override
    public DesktopApplicationsResponse listApplications() {
        return new DesktopApplicationsResponse(List.of(), 0);
    }

    @Override
    public DesktopOperationResponse openApp(OpenAppRequest request) {
        return new DesktopOperationResponse(
                true,
                "open_app",
                "Stub desktop control accepted the request",
                Map.of("appName", request == null ? null : request.appName()),
                null);
    }

    @Override
    public DesktopOperationResponse openUrl(OpenUrlRequest request) {
        return new DesktopOperationResponse(
                true,
                "open_url",
                "Stub desktop control accepted the request",
                Map.of(
                        "url", request == null ? null : request.url(),
                        "browser", request == null ? null : request.browser()),
                null);
    }

    @Override
    public DesktopOperationResponse openFile(OpenFileRequest request) {
        return new DesktopOperationResponse(
                true,
                "open_file",
                "Stub desktop control accepted the request",
                Map.of("path", request == null ? "" : request.path()),
                null);
    }

    @Override
    public DesktopSystemInfo getSystemInfo() {
        return new DesktopSystemInfo(
                "linux",
                "stub",
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                "stub",
                "stub",
                "headless",
                List.of());
    }

    @Override
    public VolumeState getVolume() {
        return new VolumeState(0, false, "stub");
    }

    @Override
    public VolumeState setVolume(int level) {
        int boundedLevel = Math.max(0, Math.min(100, level));
        return new VolumeState(boundedLevel, false, "stub");
    }

    @Override
    public DesktopOperationResponse focusWindow(WindowFocusRequest request) {
        return new DesktopOperationResponse(
                true,
                "window_focus",
                "Stub desktop control accepted the request",
                Map.of("target", request == null ? "" :
                        (request.windowId() != null ? request.windowId() : request.windowName())),
                null);
    }

    @Override
    public WindowInfo getActiveWindow() {
        return new WindowInfo("0", "Stub Window", "stub", 0);
    }

    @Override
    public WindowListResponse listWindows() {
        return new WindowListResponse(List.of(), 0);
    }

    @Override
    public DesktopOperationResponse sendKeys(SendKeysRequest request) {
        return new DesktopOperationResponse(
                true,
                "send_keys",
                "Stub desktop control accepted the request",
                Map.of("keys", request == null ? "" : request.keys()),
                null);
    }

    @Override
    public DesktopOperationResponse mouseClick(MouseClickRequest request) {
        return new DesktopOperationResponse(
                true,
                "mouse_click",
                "Stub desktop control accepted the request",
                Map.of("x", request == null ? 0 : request.x(),
                        "y", request == null ? 0 : request.y(),
                        "button", request == null ? 1 : request.button()),
                null);
    }

    @Override
    public DesktopOperationResponse mouseMove(MouseMoveRequest request) {
        return new DesktopOperationResponse(
                true,
                "mouse_move",
                "Stub desktop control accepted the request",
                Map.of("x", request == null ? 0 : request.x(),
                        "y", request == null ? 0 : request.y()),
                null);
    }

    @Override
    public DesktopOperationResponse scroll(ScrollRequest request) {
        return new DesktopOperationResponse(
                true,
                "scroll",
                "Stub desktop control accepted the request",
                Map.of("direction", request == null ? "down" : request.direction(),
                        "amount", request == null ? 0 : request.amount()),
                null);
    }
}

