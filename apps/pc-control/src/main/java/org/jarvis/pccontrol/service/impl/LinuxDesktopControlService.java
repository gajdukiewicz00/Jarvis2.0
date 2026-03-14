package org.jarvis.pccontrol.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.exception.MissingToolException;
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
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pc-control.stub-mode", havingValue = "false", matchIfMissing = true)
public class LinuxDesktopControlService implements DesktopControlService {

    private final DesktopEntryApplicationCatalog applicationCatalog;
    private final LinuxBrowserControl browserControl;
    private final LinuxAudioControl audioControl;
    private final LinuxSystemInfoProvider systemInfoProvider;
    private final LinuxWindowControl windowControl;
    private final LinuxInputControl inputControl;
    private final CommandExecutor commandExecutor;
    private final CommandLocator commandLocator;

    @Override
    public DesktopApplicationsResponse listApplications() {
        return new DesktopApplicationsResponse(applicationCatalog.listApplications(), 0);
    }

    @Override
    public DesktopOperationResponse openApp(OpenAppRequest request) throws IOException {
        String appName = request == null ? null : request.appName();
        try {
            DesktopLaunchTarget target = applicationCatalog.resolve(appName);
            commandExecutor.start(target.command());
            log.info("Launched desktop application {}", target.application().name());
            return new DesktopOperationResponse(
                    true,
                    "open_app",
                    "Application launch initiated",
                    Map.of(
                            "name", target.application().name(),
                            "desktopId", target.application().desktopId(),
                            "sourcePath", target.application().sourcePath()),
                    null);
        } catch (IllegalArgumentException e) {
            if (browserControl.isGenericBrowserAlias(appName)) {
                return browserControl.launchBrowser(null);
            }
            throw e;
        }
    }

    @Override
    public DesktopOperationResponse openUrl(OpenUrlRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("Open URL request is required");
        }
        return browserControl.openUrl(request.url(), request.browser());
    }

    @Override
    public DesktopOperationResponse openFile(OpenFileRequest request) throws IOException {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("File path is required");
        }
        if (!commandLocator.isAvailable("xdg-open")) {
            throw new MissingToolException("xdg-open");
        }
        String path = request.path().trim();
        log.info("Opening file with xdg-open: {}", path);
        commandExecutor.start(List.of("xdg-open", path));
        return new DesktopOperationResponse(
                true,
                "open_file",
                "File open initiated",
                Map.of("path", path),
                null);
    }

    @Override
    public DesktopSystemInfo getSystemInfo() {
        return systemInfoProvider.getSystemInfo();
    }

    @Override
    public VolumeState getVolume() throws IOException, InterruptedException {
        return audioControl.getVolume();
    }

    @Override
    public VolumeState setVolume(int level) throws IOException, InterruptedException {
        return audioControl.setVolume(level);
    }

    @Override
    public DesktopOperationResponse focusWindow(WindowFocusRequest request) throws IOException, InterruptedException {
        return windowControl.focusWindow(request);
    }

    @Override
    public WindowInfo getActiveWindow() throws IOException, InterruptedException {
        return windowControl.getActiveWindow();
    }

    @Override
    public WindowListResponse listWindows() throws IOException, InterruptedException {
        return windowControl.listWindows();
    }

    @Override
    public DesktopOperationResponse sendKeys(SendKeysRequest request) throws IOException, InterruptedException {
        return inputControl.sendKeys(request);
    }

    @Override
    public DesktopOperationResponse mouseClick(MouseClickRequest request) throws IOException, InterruptedException {
        return inputControl.mouseClick(request);
    }

    @Override
    public DesktopOperationResponse mouseMove(MouseMoveRequest request) throws IOException, InterruptedException {
        return inputControl.mouseMove(request);
    }

    @Override
    public DesktopOperationResponse scroll(ScrollRequest request) throws IOException, InterruptedException {
        return inputControl.scroll(request);
    }
}

