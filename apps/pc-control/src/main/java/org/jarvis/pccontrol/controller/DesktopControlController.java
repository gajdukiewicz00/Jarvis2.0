package org.jarvis.pccontrol.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.jarvis.pccontrol.model.SetVolumeRequest;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.model.WindowListResponse;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/v1/pc/desktop")
@RequiredArgsConstructor
public class DesktopControlController {

    private final DesktopControlService desktopControlService;

    @GetMapping("/apps")
    public ResponseEntity<DesktopApplicationsResponse> listApplications() {
        return ResponseEntity.ok(desktopControlService.listApplications());
    }

    @PostMapping("/apps/open")
    public ResponseEntity<DesktopOperationResponse> openApp(@RequestBody OpenAppRequest request) throws IOException {
        log.info("Desktop open_app request: {}", request == null ? null : request.appName());
        return ResponseEntity.ok(desktopControlService.openApp(request));
    }

    @PostMapping("/url/open")
    public ResponseEntity<DesktopOperationResponse> openUrl(@RequestBody OpenUrlRequest request) throws IOException {
        log.info("Desktop open_url request: {}", request == null ? null : request.url());
        return ResponseEntity.ok(desktopControlService.openUrl(request));
    }

    @PostMapping("/file/open")
    public ResponseEntity<DesktopOperationResponse> openFile(@RequestBody OpenFileRequest request) throws IOException {
        log.info("Desktop open_file request: {}", request == null ? null : request.path());
        return ResponseEntity.ok(desktopControlService.openFile(request));
    }

    @GetMapping("/system-info")
    public ResponseEntity<DesktopSystemInfo> getSystemInfo() {
        return ResponseEntity.ok(desktopControlService.getSystemInfo());
    }

    @GetMapping("/volume")
    public ResponseEntity<VolumeState> getVolume() throws IOException, InterruptedException {
        return ResponseEntity.ok(desktopControlService.getVolume());
    }

    @PostMapping("/volume")
    public ResponseEntity<VolumeState> setVolume(@RequestBody SetVolumeRequest request)
            throws IOException, InterruptedException {
        if (request == null || request.level() == null) {
            throw new IllegalArgumentException("Volume level is required");
        }
        return ResponseEntity.ok(desktopControlService.setVolume(request.level()));
    }

    @PostMapping("/window/focus")
    public ResponseEntity<DesktopOperationResponse> focusWindow(@RequestBody WindowFocusRequest request)
            throws IOException, InterruptedException {
        log.info("Desktop window_focus request: id={}, name={}",
                request == null ? null : request.windowId(),
                request == null ? null : request.windowName());
        return ResponseEntity.ok(desktopControlService.focusWindow(request));
    }

    @GetMapping("/window/active")
    public ResponseEntity<WindowInfo> getActiveWindow() throws IOException, InterruptedException {
        return ResponseEntity.ok(desktopControlService.getActiveWindow());
    }

    @GetMapping("/window/list")
    public ResponseEntity<WindowListResponse> listWindows() throws IOException, InterruptedException {
        return ResponseEntity.ok(desktopControlService.listWindows());
    }

    @PostMapping("/input/keys")
    public ResponseEntity<DesktopOperationResponse> sendKeys(@RequestBody SendKeysRequest request)
            throws IOException, InterruptedException {
        log.info("Desktop send_keys request: {}", request == null ? null : request.keys());
        return ResponseEntity.ok(desktopControlService.sendKeys(request));
    }

    @PostMapping("/input/click")
    public ResponseEntity<DesktopOperationResponse> mouseClick(@RequestBody MouseClickRequest request)
            throws IOException, InterruptedException {
        log.info("Desktop mouse_click request: x={}, y={}, button={}",
                request == null ? null : request.x(),
                request == null ? null : request.y(),
                request == null ? null : request.button());
        return ResponseEntity.ok(desktopControlService.mouseClick(request));
    }

    @PostMapping("/input/move")
    public ResponseEntity<DesktopOperationResponse> mouseMove(@RequestBody MouseMoveRequest request)
            throws IOException, InterruptedException {
        log.info("Desktop mouse_move request: x={}, y={}",
                request == null ? null : request.x(),
                request == null ? null : request.y());
        return ResponseEntity.ok(desktopControlService.mouseMove(request));
    }

    @PostMapping("/input/scroll")
    public ResponseEntity<DesktopOperationResponse> scroll(@RequestBody ScrollRequest request)
            throws IOException, InterruptedException {
        log.info("Desktop scroll request: direction={}, amount={}",
                request == null ? null : request.direction(),
                request == null ? null : request.amount());
        return ResponseEntity.ok(desktopControlService.scroll(request));
    }
}

