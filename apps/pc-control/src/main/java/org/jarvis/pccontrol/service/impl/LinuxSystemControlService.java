package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.OpenAppRequest;
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
    public void executeHotkey(String keyCombination) throws IOException, InterruptedException {
        // e.g. "Alt+Tab", "Control+c"
        validateHotkey(keyCombination);
        List<String> cmd = List.of("xdotool", "key", keyCombination);
        log.info("⌨️ Executing hotkey: {}", keyCombination);
        execWithFallback(cmd, "Hotkey");
    }

    @Override
    public void sendNotification(String title, String message) throws IOException, InterruptedException {
        List<String> cmd = List.of("notify-send", title, message);
        log.info("📢 Sending notification: {} - {}", title, message);
        execWithFallback(cmd, "Notification");
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
