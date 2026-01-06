package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.SystemControlService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Linux implementation of SystemControlService.
 * 
 * Использует pactl, playerctl, xdotool, notify-send и другие утилиты Linux
 * для управления системой. Требует наличия X11 и PulseAudio.
 * 
 * НЕ активируется в профиле "k8s" - там используется StubSystemControlService.
 */
@Slf4j
@Service
@Profile("!k8s") // Не активировать в Kubernetes - там нет X11/Pulse
public class LinuxSystemControlService implements SystemControlService {

    @Override
    public void changeVolume(int deltaPercent, String direction) throws Exception {
        int delta = Math.max(1, Math.min(100, deltaPercent));
        String sign = "+".equals(direction) ? "+" : "-";
        String cmd = "pactl set-sink-volume @DEFAULT_SINK@ " + sign + delta + "%";
        log.info("🔊 Executing volume change: {}", cmd);
        execWithFallback(cmd, "Volume change");
    }

    @Override
    public void setVolume(int percent) throws Exception {
        int level = Math.max(0, Math.min(100, percent));
        String cmd = "pactl set-sink-volume @DEFAULT_SINK@ " + level + "%";
        log.info("🔊 Setting volume to {}%: {}", level, cmd);
        execWithFallback(cmd, "Set volume");
    }

    @Override
    public void mute() throws Exception {
        String cmd = "pactl set-sink-mute @DEFAULT_SINK@ 1";
        log.info("🔇 Executing mute");
        execWithFallback(cmd, "Mute");
    }

    @Override
    public void unmute() throws Exception {
        String cmd = "pactl set-sink-mute @DEFAULT_SINK@ 0";
        log.info("🔊 Executing unmute");
        execWithFallback(cmd, "Unmute");
    }

    @Override
    public void playPause() throws Exception {
        String cmd = "playerctl play-pause";
        log.info("⏯️ Executing play/pause");
        execPlayerctl(cmd, "Play/Pause");
    }

    @Override
    public void pause() throws Exception {
        String cmd = "playerctl pause";
        log.info("⏸️ Executing pause");
        execPlayerctl(cmd, "Pause");
    }

    @Override
    public void next() throws Exception {
        String cmd = "playerctl next";
        log.info("⏭️ Executing next track");
        execPlayerctl(cmd, "Next");
    }

    @Override
    public void prev() throws Exception {
        String cmd = "playerctl previous";
        log.info("⏮️ Executing previous track");
        execPlayerctl(cmd, "Previous");
    }

    @Override
    public void beep() {
        try {
            execWithFallback(
                    "bash -lc 'paplay /usr/share/sounds/freedesktop/stereo/bell.oga 2>/dev/null || printf \"\\a\"'",
                    "Beep");
        } catch (Exception e) {
            log.debug("beep fallback: {}", e.getMessage());
            System.out.print("\007");
        }
    }

    @Override
    public void openApp(String appName) throws Exception {
        String cmd = switch (appName.toLowerCase()) {
            case "browser", "chrome", "firefox" -> "xdg-open https://google.com";
            case "youtube" -> "xdg-open https://youtube.com";
            case "spotify" -> "spotify";
            case "ide", "vscode", "code" -> "code";
            case "calculator", "calc" -> "gnome-calculator";
            case "terminal" -> "gnome-terminal";
            case "telegram" -> "telegram-desktop";
            default -> "xdg-open " + appName;
        };
        log.info("🚀 Opening app: {} with command: {}", appName, cmd);
        // Run in background
        new ProcessBuilder("bash", "-lc", "nohup " + cmd + " >/dev/null 2>&1 &").start();
    }

    @Override
    public void executeHotkey(String keyCombination) throws Exception {
        // e.g. "Alt+Tab", "Control+c"
        String cmd = "xdotool key " + keyCombination;
        log.info("⌨️ Executing hotkey: {}", keyCombination);
        execWithFallback(cmd, "Hotkey");
    }

    @Override
    public void sendNotification(String title, String message) throws Exception {
        String cmd = "notify-send " + shellQuote(title) + " " + shellQuote(message);
        log.info("📢 Sending notification: {} - {}", title, message);
        execWithFallback(cmd, "Notification");
    }

    /**
     * Execute command with graceful error handling.
     * Logs warning instead of throwing for non-critical failures.
     */
    private void execWithFallback(String cmd, String operation) throws Exception {
        try {
            Process p = new ProcessBuilder("bash", "-lc", cmd).redirectErrorStream(true).start();
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
    private void execPlayerctl(String cmd, String operation) throws Exception {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", cmd);
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

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
