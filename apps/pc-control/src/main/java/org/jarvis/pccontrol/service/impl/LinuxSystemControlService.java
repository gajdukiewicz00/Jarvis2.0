package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandResult;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.jarvis.pccontrol.service.SystemControlService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Linux implementation of SystemControlService.
 * 
 * Использует pactl, playerctl, xdotool, notify-send и другие утилиты Linux
 * для управления системой. Требует наличия X11 и PulseAudio.
 * 
 * Используется, когда pc-control.stub-mode=false.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "pc-control.stub-mode", havingValue = "false", matchIfMissing = true)
public class LinuxSystemControlService implements SystemControlService {

    private static final Pattern HOTKEY_PATTERN = Pattern.compile("^[A-Za-z0-9+_\\-]{1,64}$");
    private static final int MAX_TYPE_TEXT_LENGTH = 500;
    // Disallow ASCII control characters (newlines, escapes, etc.) so xdotool
    // never receives anything that could be interpreted as extra key events.
    private static final Pattern UNSAFE_TEXT_CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final String DEFAULT_SCREENSHOT_FILENAME = "jarvis-screenshot.png";

    private static final Map<String, List<String>> APP_COMMANDS = Map.ofEntries(
            Map.entry("browser", List.of("xdg-open", "https://google.com")),
            Map.entry("chrome", List.of("xdg-open", "https://google.com")),
            Map.entry("firefox", List.of("xdg-open", "https://google.com")),
            Map.entry("youtube", List.of("xdg-open", "https://youtube.com")),
            Map.entry("spotify", List.of("spotify")),
            Map.entry("ide", List.of("code")),
            Map.entry("vscode", List.of("code")),
            Map.entry("code", List.of("code")),
            Map.entry("calculator", List.of("gnome-calculator")),
            Map.entry("calc", List.of("gnome-calculator")),
            Map.entry("terminal", List.of("gnome-terminal")),
            Map.entry("telegram", List.of("telegram-desktop"))
    );

    private final LinuxAudioControl audioControl;
    private final DesktopControlService desktopControlService;
    private final CommandExecutor commandExecutor;
    private final Path screenshotDir;

    public LinuxSystemControlService(
            LinuxAudioControl audioControl,
            DesktopControlService desktopControlService,
            CommandExecutor commandExecutor,
            @Value("${pc-control.screenshot-dir:/tmp}") String screenshotDir) {
        this.audioControl = audioControl;
        this.desktopControlService = desktopControlService;
        this.commandExecutor = commandExecutor;
        this.screenshotDir = Paths.get(screenshotDir).toAbsolutePath().normalize();
    }

    @Override
    public void changeVolume(int deltaPercent, String direction) throws IOException, InterruptedException {
        audioControl.changeVolume(deltaPercent, direction);
    }

    @Override
    public void setVolume(int percent) throws IOException, InterruptedException {
        audioControl.setVolume(Math.max(0, Math.min(100, percent)));
    }

    @Override
    public void mute() throws IOException, InterruptedException {
        audioControl.mute();
    }

    @Override
    public void unmute() throws IOException, InterruptedException {
        audioControl.unmute();
    }

    @Override
    public void playPause() throws IOException, InterruptedException {
        List<String> cmd = List.of("playerctl", "play-pause");
        log.info("⏯️ Executing play/pause");
        execPlayerctl(cmd, "Play/Pause");
    }

    @Override
    public void pause() throws IOException, InterruptedException {
        List<String> cmd = List.of("playerctl", "pause");
        log.info("⏸️ Executing pause");
        execPlayerctl(cmd, "Pause");
    }

    @Override
    public void next() throws IOException, InterruptedException {
        List<String> cmd = List.of("playerctl", "next");
        log.info("⏭️ Executing next track");
        execPlayerctl(cmd, "Next");
    }

    @Override
    public void prev() throws IOException, InterruptedException {
        List<String> cmd = List.of("playerctl", "previous");
        log.info("⏮️ Executing previous track");
        execPlayerctl(cmd, "Previous");
    }

    @Override
    public void beep() {
        try {
            execWithFallback(
                    List.of("paplay", "/usr/share/sounds/freedesktop/stereo/bell.oga"),
                    "Beep");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("beep fallback: {}", e.getMessage());
            System.out.print("\007");
        }
    }

    @Override
    public void openApp(String appName) throws IOException {
        try {
            desktopControlService.openApp(new OpenAppRequest(appName));
        } catch (IllegalArgumentException e) {
            List<String> fallbackCommand = APP_COMMANDS.get(normalizeAppName(appName));
            if (fallbackCommand == null) {
                throw e;
            }
            log.info("Fallback app launch for {} with {}", appName, fallbackCommand);
            commandExecutor.start(fallbackCommand);
        }
    }

    @Override
    public void openUrl(String url) throws IOException {
        desktopControlService.openUrl(new OpenUrlRequest(url, null));
    }

    @Override
    public void executeHotkey(String keyCombination) throws IOException, InterruptedException {
        // e.g. "Alt+Tab", "Control+c"
        validateHotkey(keyCombination);
        List<String> cmd = List.of("xdotool", "key", keyCombination);
        log.info("⌨️ Executing hotkey: {}", keyCombination);
        execWithFallback(cmd, "Hotkey");
    }

    @Override
    public void typeText(String text) throws IOException, InterruptedException {
        // e.g. "Hello, Jarvis!" — literal characters, not a key combination.
        validateTypeText(text);
        List<String> cmd = List.of("xdotool", "type", "--", text);
        log.info("⌨️ Typing text (length={})", text.length());
        execWithFallback(cmd, "TypeText");
    }

    @Override
    public void focusWindow(String title) throws IOException, InterruptedException {
        execWindowCommand(title, List.of("windowactivate", "--sync"), "WindowFocus");
    }

    @Override
    public void closeWindow(String title) throws IOException, InterruptedException {
        execWindowCommand(title, List.of("windowclose"), "WindowClose");
    }

    @Override
    public void minimizeWindow(String title) throws IOException, InterruptedException {
        execWindowCommand(title, List.of("windowminimize"), "WindowMinimize");
    }

    @Override
    public void maximizeWindow(String title) throws IOException, InterruptedException {
        try {
            execWmctrl(title, "add,maximized_vert,maximized_horz", "WindowMaximize");
        } catch (IOException e) {
            log.warn("wmctrl maximize failed for '{}', falling back to Alt+F10", title);
            if (title != null && !title.isBlank()) {
                focusWindow(title);
            }
            executeHotkey("Alt+F10");
        }
    }

    @Override
    public void normalizeWindow(String title) throws IOException, InterruptedException {
        try {
            execWmctrl(title, "remove,maximized_vert,maximized_horz", "WindowNormalize");
        } catch (IOException e) {
            log.warn("wmctrl normalize failed for '{}', falling back to Alt+F5", title);
            if (title != null && !title.isBlank()) {
                focusWindow(title);
            }
            executeHotkey("Alt+F5");
        }
    }

    @Override
    public void moveMouseAbsolute(int x, int y) throws IOException, InterruptedException {
        execWithFallback(List.of("xdotool", "mousemove", "--sync", String.valueOf(x), String.valueOf(y)),
                "MouseMove");
    }

    @Override
    public void leftClick() throws IOException, InterruptedException {
        execWithFallback(List.of("xdotool", "click", "1"), "MouseLeftClick");
    }

    @Override
    public void rightClick() throws IOException, InterruptedException {
        execWithFallback(List.of("xdotool", "click", "3"), "MouseRightClick");
    }

    @Override
    public void leftButtonDown() throws IOException, InterruptedException {
        execWithFallback(List.of("xdotool", "mousedown", "1"), "MouseLeftDown");
    }

    @Override
    public void leftButtonUp() throws IOException, InterruptedException {
        execWithFallback(List.of("xdotool", "mouseup", "1"), "MouseLeftUp");
    }

    @Override
    public void emptyTrash() throws IOException, InterruptedException {
        try {
            execWithFallback(List.of("gio", "trash", "--empty"), "EmptyTrash");
        } catch (IOException e) {
            log.warn("gio trash empty failed, falling back to trash-empty");
            execWithFallback(List.of("trash-empty"), "EmptyTrashFallback");
        }
    }

    @Override
    public void openOpticalDrive() throws IOException, InterruptedException {
        execWithFallback(List.of("eject"), "OpenOpticalDrive");
    }

    @Override
    public void closeOpticalDrive() throws IOException, InterruptedException {
        execWithFallback(List.of("eject", "-t"), "CloseOpticalDrive");
    }

    @Override
    public void sendNotification(String title, String message) throws IOException, InterruptedException {
        List<String> cmd = List.of("notify-send", title, message);
        log.info("📢 Sending notification: {} - {}", title, message);
        execWithFallback(cmd, "Notification");
    }

    @Override
    public void sleep() throws IOException, InterruptedException {
        execWithFallback(List.of("systemctl", "suspend"), "Sleep");
    }

    @Override
    public void turnMonitorOff() throws IOException, InterruptedException {
        execWithFallback(List.of("xset", "dpms", "force", "off"), "MonitorOff");
    }

    @Override
    public void lockScreen() throws IOException, InterruptedException {
        execWithFallback(List.of("loginctl", "lock-session"), "LockScreen");
    }

    @Override
    public void takeScreenshot(String path) throws IOException, InterruptedException {
        Path target = resolveScreenshotPath(path);
        execWithFallback(List.of("gnome-screenshot", "-f", target.toString()), "Screenshot");
    }

    /**
     * Confines a caller-supplied screenshot path to {@link #screenshotDir}: relative
     * paths are resolved inside it, absolute paths are only accepted if they already
     * fall inside it once canonicalized, and any {@code ..} traversal that would
     * escape the directory is rejected outright.
     */
    private Path resolveScreenshotPath(String path) {
        String candidate = (path == null || path.isBlank()) ? DEFAULT_SCREENSHOT_FILENAME : path.trim();
        Path requested = Paths.get(candidate);
        Path resolved = (requested.isAbsolute() ? requested : screenshotDir.resolve(requested)).normalize();
        if (!resolved.startsWith(screenshotDir)) {
            throw new IllegalArgumentException("Screenshot path escapes the allowed directory: " + path);
        }
        return resolved;
    }

    /**
     * Executes a command and maps a non-zero exit code to a thrown IOException -
     * callers (and ultimately DefaultPcActionExecutionService) must see this as the
     * FAILURE it is rather than a silently-successful no-op.
     */
    private void execWithFallback(List<String> cmd, String operation) throws IOException, InterruptedException {
        CommandResult result = commandExecutor.execute(cmd);
        if (result.exitCode() != 0) {
            log.warn("⚠️ {} command returned non-zero exit code: {} for cmd: {}", operation, result.exitCode(), cmd);
            throw new IOException(operation + " failed with exit code " + result.exitCode()
                    + ": " + result.stdout());
        }
        log.debug("✅ {} completed successfully", operation);
    }

    /**
     * Execute playerctl command with special handling for "No players found" case.
     * That specific case is a common, non-erroneous edge case (nothing is playing)
     * and must not fail the action; every other non-zero exit is a real failure.
     */
    private void execPlayerctl(List<String> cmd, String operation) throws IOException, InterruptedException {
        CommandResult result = commandExecutor.execute(cmd);
        if (result.exitCode() == 0) {
            log.debug("✅ {} completed successfully", operation);
            return;
        }
        String output = result.stdout();
        if (output.contains("No players found") || output.contains("No player could handle")) {
            log.warn("⚠️ {} skipped: no media players running", operation);
            return;
        }
        log.warn("⚠️ {} command returned non-zero: {} (output: {})", operation, result.exitCode(), output);
        throw new IOException(operation + " failed with exit code " + result.exitCode() + ": " + output);
    }

    private void execWindowCommand(String title, List<String> actionArgs, String operation)
            throws IOException, InterruptedException {
        List<String> cmd;
        if (title == null || title.isBlank()) {
            cmd = List.of("xdotool", "getactivewindow", actionArgs.get(0));
            if (actionArgs.size() > 1) {
                cmd = List.of("xdotool", "getactivewindow", actionArgs.get(0), actionArgs.get(1));
            }
        } else {
            java.util.ArrayList<String> parts = new java.util.ArrayList<>();
            parts.add("xdotool");
            parts.add("search");
            parts.add("--name");
            parts.add(title);
            parts.addAll(actionArgs);
            parts.add("%@");
            cmd = List.copyOf(parts);
        }
        execWithFallback(cmd, operation);
    }

    private void execWmctrl(String title, String toggle, String operation) throws IOException, InterruptedException {
        String target = (title == null || title.isBlank()) ? ":ACTIVE:" : title;
        execWithFallback(List.of("wmctrl", "-r", target, "-b", toggle), operation);
    }

    private static void validateHotkey(String keyCombination) {
        if (keyCombination == null || keyCombination.isBlank()) {
            throw new IllegalArgumentException("Hotkey is required");
        }
        if (!HOTKEY_PATTERN.matcher(keyCombination).matches()) {
            throw new IllegalArgumentException("Invalid hotkey format");
        }
    }

    private static void validateTypeText(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text is required");
        }
        if (text.length() > MAX_TYPE_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Text exceeds maximum length of " + MAX_TYPE_TEXT_LENGTH);
        }
        if (UNSAFE_TEXT_CONTROL_CHARS.matcher(text).find()) {
            throw new IllegalArgumentException("Text contains unsafe control characters");
        }
    }

    private static String normalizeAppName(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("App name is required");
        }
        return appName.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
