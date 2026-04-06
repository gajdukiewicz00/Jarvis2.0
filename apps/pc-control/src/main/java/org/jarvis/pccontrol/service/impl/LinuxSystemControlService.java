package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.OpenAppRequest;
import org.jarvis.pccontrol.model.OpenUrlRequest;
import org.jarvis.pccontrol.service.SystemControlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
@lombok.RequiredArgsConstructor
@ConditionalOnProperty(name = "pc-control.stub-mode", havingValue = "false", matchIfMissing = true)
public class LinuxSystemControlService implements SystemControlService {

    private static final Pattern HOTKEY_PATTERN = Pattern.compile("^[A-Za-z0-9+_\\-]{1,64}$");

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
    private final org.jarvis.pccontrol.service.DesktopControlService desktopControlService;

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
            startProcess(fallbackCommand);
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

    /**
     * Execute command with graceful error handling.
     * Logs warning instead of throwing for non-critical failures.
     */
    private void execWithFallback(List<String> cmd, String operation) throws IOException, InterruptedException {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int code = p.waitFor();
            if (code != 0) {
                log.warn("⚠️ {} command returned non-zero exit code: {} for cmd: {}", operation, code, cmd);
                // Don't throw for non-critical commands - just log
            } else {
                log.debug("✅ {} completed successfully", operation);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("❌ {} failed: {}", operation, e.getMessage());
            throw e;
        }
    }

    /**
     * Execute playerctl command with special handling for "No players found" case.
     * This is a common edge case that should not crash the service.
     */
    private void execPlayerctl(List<String> cmd, String operation) throws IOException, InterruptedException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read output for error checking
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int code = p.waitFor();

            if (code != 0) {
                if (output.contains("No players found") || output.contains("No player could handle")) {
                    log.warn("⚠️ {} skipped: no media players running", operation);
                    // This is not an error - just no players available
                    return;
                }
                log.warn("⚠️ {} command returned non-zero: {} (output: {})", operation, code, output);
            } else {
                log.debug("✅ {} completed successfully", operation);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("❌ {} failed: {}", operation, e.getMessage());
            // Don't throw for playerctl - just log the error
        }
    }

    private static void startProcess(List<String> cmd) throws IOException {
        new ProcessBuilder(cmd).start();
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

    private static String normalizeAppName(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("App name is required");
        }
        return appName.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
