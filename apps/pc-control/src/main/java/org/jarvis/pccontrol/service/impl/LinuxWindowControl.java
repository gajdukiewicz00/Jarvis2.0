package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.exception.UnsupportedDisplayServerException;
import org.jarvis.pccontrol.exception.WindowNotFoundException;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.model.WindowListResponse;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.CommandResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LinuxWindowControl {

    private static final Pattern WMCTRL_LINE = Pattern.compile(
            "^(0x[0-9a-fA-F]+)\\s+([-\\d]+)\\s+\\S+\\s+(.+)$");

    private final CommandExecutor commandExecutor;
    private final CommandLocator commandLocator;
    private final Supplier<String> displayServerDetector;

    @Autowired
    public LinuxWindowControl(CommandExecutor commandExecutor, CommandLocator commandLocator) {
        this(commandExecutor, commandLocator, LinuxWindowControl::detectDisplayServer);
    }

    LinuxWindowControl(CommandExecutor commandExecutor, CommandLocator commandLocator,
                        Supplier<String> displayServerDetector) {
        this.commandExecutor = commandExecutor;
        this.commandLocator = commandLocator;
        this.displayServerDetector = displayServerDetector;
    }

    public DesktopOperationResponse focusWindow(WindowFocusRequest request) throws IOException, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("Window focus request is required");
        }
        boolean hasId = request.windowId() != null && !request.windowId().isBlank();
        boolean hasName = request.windowName() != null && !request.windowName().isBlank();
        if (!hasId && !hasName) {
            throw new IllegalArgumentException("Either windowId or windowName is required");
        }

        if (hasId) {
            validateWindowId(request.windowId());
        }

        requireWindowTool();

        if (commandLocator.isAvailable("wmctrl")) {
            return focusWithWmctrl(request, hasId);
        }
        return focusWithXdotool(request, hasId);
    }

    public WindowInfo getActiveWindow() throws IOException, InterruptedException {
        requireDisplayServer();
        if (!commandLocator.isAvailable("xdotool")) {
            throw new MissingToolException("xdotool");
        }

        CommandResult idResult = commandExecutor.execute(List.of("xdotool", "getactivewindow"));
        if (idResult.exitCode() != 0) {
            throw new IOException("Failed to get active window: " + idResult.stdout());
        }
        String windowId = idResult.stdout().trim();

        CommandResult nameResult = commandExecutor.execute(
                List.of("xdotool", "getactivewindow", "getwindowname"));
        String title = nameResult.exitCode() == 0 ? nameResult.stdout().trim() : "";

        String wmClass = "";
        if (commandLocator.isAvailable("xprop")) {
            CommandResult xpropResult = commandExecutor.execute(
                    List.of("xprop", "-id", windowId, "WM_CLASS"));
            if (xpropResult.exitCode() == 0) {
                wmClass = parseWmClass(xpropResult.stdout());
            }
        }

        return new WindowInfo(windowId, title, wmClass, -1);
    }

    public WindowListResponse listWindows() throws IOException, InterruptedException {
        requireWindowTool();

        if (commandLocator.isAvailable("wmctrl")) {
            return listWithWmctrl();
        }
        return listWithXdotool();
    }

    private void requireWindowTool() {
        requireDisplayServer();
        if (!commandLocator.isAvailable("wmctrl") && !commandLocator.isAvailable("xdotool")) {
            throw new MissingToolException("wmctrl/xdotool");
        }
    }

    private void requireDisplayServer() {
        String ds = displayServerDetector.get();
        if ("headless".equals(ds)) {
            throw new UnsupportedDisplayServerException("headless", "x11");
        }
    }

    private DesktopOperationResponse focusWithWmctrl(WindowFocusRequest request, boolean hasId)
            throws IOException, InterruptedException {
        List<String> command;
        if (hasId) {
            command = List.of("wmctrl", "-i", "-a", request.windowId());
        } else {
            command = List.of("wmctrl", "-a", request.windowName());
        }
        CommandResult result = commandExecutor.execute(command);
        if (result.exitCode() != 0) {
            String identifier = hasId ? request.windowId() : request.windowName();
            throw new WindowNotFoundException(identifier);
        }
        log.info("wmctrl focus result: exitCode={}, target={}", result.exitCode(),
                hasId ? request.windowId() : request.windowName());
        return new DesktopOperationResponse(
                true,
                "window_focus",
                "Window focused",
                Map.of("backend", "wmctrl",
                        "target", hasId ? request.windowId() : request.windowName()),
                null);
    }

    private DesktopOperationResponse focusWithXdotool(WindowFocusRequest request, boolean hasId)
            throws IOException, InterruptedException {
        List<String> command;
        if (hasId) {
            command = List.of("xdotool", "windowactivate", request.windowId());
        } else {
            CommandResult search = commandExecutor.execute(
                    List.of("xdotool", "search", "--name", request.windowName()));
            if (search.exitCode() != 0 || search.stdout().isBlank()) {
                throw new WindowNotFoundException(request.windowName());
            }
            String firstId = search.stdout().lines().findFirst().orElse("").trim();
            command = List.of("xdotool", "windowactivate", firstId);
        }
        CommandResult result = commandExecutor.execute(command);
        if (result.exitCode() != 0 && hasId) {
            throw new WindowNotFoundException(request.windowId());
        }
        return new DesktopOperationResponse(
                true,
                "window_focus",
                "Window focused",
                Map.of("backend", "xdotool",
                        "target", hasId ? request.windowId() : request.windowName()),
                null);
    }

    private WindowListResponse listWithWmctrl() throws IOException, InterruptedException {
        CommandResult result = commandExecutor.execute(List.of("wmctrl", "-l"));
        if (result.exitCode() != 0) {
            throw new IOException("wmctrl -l failed: " + result.stdout());
        }
        List<WindowInfo> windows = new ArrayList<>();
        for (String line : result.stdout().lines().toList()) {
            Matcher m = WMCTRL_LINE.matcher(line);
            if (m.matches()) {
                windows.add(new WindowInfo(
                        m.group(1),
                        m.group(3).trim(),
                        "",
                        parseDesktop(m.group(2))));
            }
        }
        return new WindowListResponse(windows, windows.size());
    }

    private WindowListResponse listWithXdotool() throws IOException, InterruptedException {
        CommandResult result = commandExecutor.execute(
                List.of("xdotool", "search", "--name", ""));
        if (result.exitCode() != 0) {
            return new WindowListResponse(List.of(), 0);
        }
        List<WindowInfo> windows = new ArrayList<>();
        for (String idLine : result.stdout().lines().toList()) {
            String id = idLine.trim();
            if (id.isEmpty()) continue;
            CommandResult nameResult = commandExecutor.execute(
                    List.of("xdotool", "getwindowname", id));
            String title = nameResult.exitCode() == 0 ? nameResult.stdout().trim() : "";
            windows.add(new WindowInfo(id, title, "", -1));
        }
        return new WindowListResponse(windows, windows.size());
    }

    static String parseWmClass(String xpropOutput) {
        if (xpropOutput == null || !xpropOutput.contains("=")) {
            return "";
        }
        String value = xpropOutput.substring(xpropOutput.indexOf('=') + 1).trim();
        return value.replace("\"", "").replace(",", "").trim();
    }

    static void validateWindowId(String windowId) {
        if (windowId.startsWith("0x") || windowId.startsWith("0X")) {
            if (!windowId.substring(2).matches("[0-9a-fA-F]+")) {
                throw new IllegalArgumentException("Invalid hex window ID: " + windowId);
            }
        } else {
            if (!windowId.matches("\\d+")) {
                throw new IllegalArgumentException("Invalid window ID: " + windowId);
            }
        }
    }

    private static int parseDesktop(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String detectDisplayServer() {
        if (System.getenv("WAYLAND_DISPLAY") != null) return "wayland";
        if (System.getenv("DISPLAY") != null) return "x11";
        return "headless";
    }
}
