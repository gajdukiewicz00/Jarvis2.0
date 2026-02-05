package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.SystemControlService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
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

    @Override
    public void changeVolume(int deltaPercent, String direction) throws Exception {
        int delta = Math.max(1, Math.min(100, deltaPercent));
        String sign = "+".equals(direction) ? "+" : "-";
        List<String> cmd = List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", sign + delta + "%");
        log.info("🔊 Executing volume change: {}", cmd);
        execWithFallback(cmd, "Volume change");
    }

    @Override
    public void setVolume(int percent) throws Exception {
        int level = Math.max(0, Math.min(100, percent));
        List<String> cmd = List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", level + "%");
        log.info("🔊 Setting volume to {}%: {}", level, cmd);
        execWithFallback(cmd, "Set volume");
    }

    @Override
    public void mute() throws Exception {
        List<String> cmd = List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", "1");
        log.info("🔇 Executing mute");
        execWithFallback(cmd, "Mute");
    }

    @Override
    public void unmute() throws Exception {
        List<String> cmd = List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", "0");
        log.info("🔊 Executing unmute");
        execWithFallback(cmd, "Unmute");
    }

    @Override
    public void playPause() throws Exception {
        List<String> cmd = List.of("playerctl", "play-pause");
        log.info("⏯️ Executing play/pause");
        execPlayerctl(cmd, "Play/Pause");
    }

    @Override
    public void pause() throws Exception {
        List<String> cmd = List.of("playerctl", "pause");
        log.info("⏸️ Executing pause");
        execPlayerctl(cmd, "Pause");
    }

    @Override
    public void next() throws Exception {
        List<String> cmd = List.of("playerctl", "next");
        log.info("⏭️ Executing next track");
        execPlayerctl(cmd, "Next");
    }

    @Override
    public void prev() throws Exception {
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
        } catch (Exception e) {
            log.debug("beep fallback: {}", e.getMessage());
            System.out.print("\007");
        }
    }

    @Override
    public void openApp(String appName) throws Exception {
        List<String> cmd = resolveAppCommand(appName);
        log.info("🚀 Opening app: {} with command: {}", appName, cmd);
        startProcess(cmd);
    }

    @Override
    public void executeHotkey(String keyCombination) throws Exception {
        // e.g. "Alt+Tab", "Control+c"
        validateHotkey(keyCombination);
        List<String> cmd = List.of("xdotool", "key", keyCombination);
        log.info("⌨️ Executing hotkey: {}", keyCombination);
        execWithFallback(cmd, "Hotkey");
    }

    @Override
    public void sendNotification(String title, String message) throws Exception {
        List<String> cmd = List.of("notify-send", title, message);
        log.info("📢 Sending notification: {} - {}", title, message);
        execWithFallback(cmd, "Notification");
    }

    /**
     * Execute command with graceful error handling.
     * Logs warning instead of throwing for non-critical failures.
     */
    private void execWithFallback(List<String> cmd, String operation) throws Exception {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int code = p.waitFor();
            if (code != 0) {
                log.warn("⚠️ {} command returned non-zero exit code: {} for cmd: {}", operation, code, cmd);
                // Don't throw for non-critical commands - just log
            } else {
                log.debug("✅ {} completed successfully", operation);
            }
        } catch (Exception e) {
            log.error("❌ {} failed: {}", operation, e.getMessage());
            throw e;
        }
    }

    /**
     * Execute playerctl command with special handling for "No players found" case.
     * This is a common edge case that should not crash the service.
     */
    private void execPlayerctl(List<String> cmd, String operation) throws Exception {
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
        } catch (Exception e) {
            log.error("❌ {} failed: {}", operation, e.getMessage());
            // Don't throw for playerctl - just log the error
        }
    }

    private static void startProcess(List<String> cmd) throws Exception {
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

    private static List<String> resolveAppCommand(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("App name is required");
        }
        String key = appName.toLowerCase(Locale.ROOT).trim();
        List<String> cmd = APP_COMMANDS.get(key);
        if (cmd == null) {
            throw new IllegalArgumentException("Unsupported app: " + appName);
        }
        return cmd;
    }
}
