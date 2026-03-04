package org.jarvis.pccontrol.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.SystemControlService;
import org.jarvis.pccontrol.service.TimerLimitExceededException;
import org.jarvis.pccontrol.service.TimerSchedulerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PC control controller for system operations.
 * 
 * Supports volume control, app launching, hotkeys, and timers.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pc")
@RequiredArgsConstructor
public class PcControlController {

    private final SystemControlService systemControlService;
    private final TimerSchedulerService timerSchedulerService;

    /**
     * Execute a PC control action.
     * 
     * Supported action types:
     * - MEDIA_CONTROL: Change volume (parameters: deltaPercent, direction)
     * - OPEN_APP: Launch application (parameters: appName)
     * - HOTKEY: Execute keyboard shortcut (parameters: keyCombination)
     * - SYSTEM_COMMAND: Execute system command (e.g., timer)
     */
    @PostMapping("/action")
    public ResponseEntity<Map<String, Object>> executeAction(@RequestBody ActionRequest request) {
        log.info("Received PC action: type={}, parameters={}",
                request.actionType(), request.parameters());

        if (request.actionType() == null || request.actionType().isBlank()) {
            return badRequest("INVALID_ACTION_TYPE", "Action type is required");
        }

        try {
            Map<String, Object> result = switch (request.actionType()) {
                case "MEDIA_CONTROL" -> handleMediaControl(request);
                case "VOLUME_UP" -> handleVolumeUp(request);
                case "VOLUME_DOWN" -> handleVolumeDown(request);
                case "SET_VOLUME" -> handleSetVolume(request);
                case "MUTE" -> handleMute();
                case "UNMUTE" -> handleUnmute();
                case "PLAY_PAUSE" -> handlePlayPause();
                case "PAUSE" -> handlePause();
                case "NEXT" -> handleNext();
                case "PREV" -> handlePrev();
                case "OPEN_APP" -> handleOpenApp(request);
                case "HOTKEY" -> handleHotkey(request);
                case "NOTIFY" -> handleNotify(request);
                case "SYSTEM_COMMAND" -> handleSystemCommand(request);
                default -> {
                    log.warn("Unknown action type: {}", request.actionType());
                    yield Map.of(
                            "success", false,
                            "error", "UNKNOWN_ACTION_TYPE",
                            "message", "Unknown action type: " + request.actionType());
                }
            };

            if (Boolean.FALSE.equals(result.get("success"))) {
                return ResponseEntity.badRequest().body(result);
            }

            return ResponseEntity.ok(result);

        } catch (NumberFormatException e) {
            return badRequest("INVALID_PARAMETER", "Invalid number format: " + e.getMessage());
        } catch (TimerLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "success", false,
                    "actionType", request.actionType(),
                    "error", "TIMER_LIMIT_EXCEEDED",
                    "message", e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()));
        } catch (IllegalArgumentException e) {
            return badRequest("INVALID_PARAMETER", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Action execution interrupted: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "actionType", request.actionType(),
                    "error", "EXECUTION_FAILED",
                    "message", "Failed to execute action: " + e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()));
        } catch (IOException | RuntimeException e) {
            log.error("Action execution failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "actionType", request.actionType(),
                    "error", "EXECUTION_FAILED",
                    "message", "Failed to execute action: " + e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()));
        }
    }

    private Map<String, Object> handleMediaControl(ActionRequest request) throws IOException, InterruptedException {
        int delta = Integer.parseInt(request.parameters().getOrDefault("deltaPercent", "5"));
        String direction = request.parameters().getOrDefault("direction", "+");

        systemControlService.changeVolume(delta, direction);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("actionType", "MEDIA_CONTROL");
        result.put("volumeChange", direction + delta + "%");
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("message", "Volume changed successfully");
        return result;
    }

    private Map<String, Object> handleOpenApp(ActionRequest request) throws IOException {
        String appName = request.parameters().get("appName");
        if (appName == null || appName.isBlank()) {
            return Map.of(
                    "success", false,
                    "error", "MISSING_PARAMETER",
                    "message", "Parameter 'appName' is required");
        }

        systemControlService.openApp(appName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("actionType", "OPEN_APP");
        result.put("appName", appName);
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("message", "Application launch initiated");
        return result;
    }

    private Map<String, Object> handleHotkey(ActionRequest request) throws IOException, InterruptedException {
        String keyCombination = request.parameters().get("keyCombination");
        if (keyCombination == null || keyCombination.isBlank()) {
            return Map.of(
                    "success", false,
                    "error", "MISSING_PARAMETER",
                    "message", "Parameter 'keyCombination' is required");
        }

        systemControlService.executeHotkey(keyCombination);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("actionType", "HOTKEY");
        result.put("keyCombination", keyCombination);
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("message", "Hotkey executed successfully");
        return result;
    }

    // ==================== Volume Control ====================

    private Map<String, Object> handleVolumeUp(ActionRequest request) throws IOException, InterruptedException {
        int delta = Integer.parseInt(request.parameters().getOrDefault("delta", "10"));
        systemControlService.changeVolume(delta, "+");
        return successResult("VOLUME_UP", "Volume increased by " + delta + "%");
    }

    private Map<String, Object> handleVolumeDown(ActionRequest request) throws IOException, InterruptedException {
        int delta = Integer.parseInt(request.parameters().getOrDefault("delta", "10"));
        systemControlService.changeVolume(delta, "-");
        return successResult("VOLUME_DOWN", "Volume decreased by " + delta + "%");
    }

    private Map<String, Object> handleSetVolume(ActionRequest request) throws IOException, InterruptedException {
        int level = Integer.parseInt(request.parameters().getOrDefault("level", "100"));
        level = Math.max(0, Math.min(100, level)); // Clamp to 0-100%
        systemControlService.setVolume(level);
        return successResult("SET_VOLUME", "Volume set to " + level + "%");
    }

    private Map<String, Object> handleMute() throws IOException, InterruptedException {
        systemControlService.mute();
        return successResult("MUTE", "Sound muted");
    }

    private Map<String, Object> handleUnmute() throws IOException, InterruptedException {
        systemControlService.unmute();
        return successResult("UNMUTE", "Sound unmuted");
    }

    // ==================== Media Control ====================

    private Map<String, Object> handlePlayPause() throws IOException, InterruptedException {
        systemControlService.playPause();
        return successResult("PLAY_PAUSE", "Playback toggled");
    }

    private Map<String, Object> handlePause() throws IOException, InterruptedException {
        systemControlService.pause();
        return successResult("PAUSE", "Playback paused");
    }

    private Map<String, Object> handleNext() throws IOException, InterruptedException {
        systemControlService.next();
        return successResult("NEXT", "Skipped to next track");
    }

    private Map<String, Object> handlePrev() throws IOException, InterruptedException {
        systemControlService.prev();
        return successResult("PREV", "Returned to previous track");
    }

    // ==================== Notifications ====================

    private Map<String, Object> handleNotify(ActionRequest request) throws IOException, InterruptedException {
        String title = request.parameters().getOrDefault("title", "Jarvis");
        String message = request.parameters().getOrDefault("message", "Notification");
        systemControlService.sendNotification(title, message);
        return successResult("NOTIFY", "Notification sent");
    }

    private Map<String, Object> successResult(String actionType, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("actionType", actionType);
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("message", message);
        return result;
    }

    private Map<String, Object> handleSystemCommand(ActionRequest request) {
        String command = request.parameters().get("command");

        if ("timer".equals(command)) {
            String args = request.parameters().getOrDefault("args", "0");
            String digits = args.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return Map.of(
                        "success", false,
                        "error", "INVALID_PARAMETER",
                        "message", "Timer duration is required");
            }
            int seconds = Integer.parseInt(digits);
            if (seconds < 1 || seconds > 86400) {
                return Map.of(
                        "success", false,
                        "error", "INVALID_PARAMETER",
                        "message", "Timer duration must be between 1 and 86400 seconds");
            }

            String timerId = timerSchedulerService.scheduleTimer(seconds, () -> {
                try {
                    systemControlService.sendNotification("Timer", "Time is up!");
                    systemControlService.beep();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Timer callback failed", e);
                }
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("actionType", "SYSTEM_COMMAND");
            result.put("command", "timer");
            result.put("timerId", timerId);
            result.put("durationSeconds", seconds);
            result.put("timestamp", LocalDateTime.now().toString());
            result.put("message", "Timer started for " + seconds + " seconds");
            return result;
        }

        if ("cancel_timer".equals(command)) {
            String timerId = request.parameters().getOrDefault("timerId", request.parameters().getOrDefault("args", ""));
            if (timerId.isBlank()) {
                return Map.of(
                        "success", false,
                        "error", "INVALID_PARAMETER",
                        "message", "Parameter 'timerId' is required");
            }
            boolean cancelled = timerSchedulerService.cancelTimer(timerId);
            if (!cancelled) {
                return Map.of(
                        "success", false,
                        "error", "TIMER_NOT_FOUND",
                        "message", "Timer not found: " + timerId);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("actionType", "SYSTEM_COMMAND");
            result.put("command", "cancel_timer");
            result.put("timerId", timerId);
            result.put("timestamp", LocalDateTime.now().toString());
            result.put("message", "Timer cancelled");
            return result;
        }

        return Map.of(
                "success", false,
                "error", "UNKNOWN_COMMAND",
                "message", "Unknown system command: " + command);
    }

    /**
     * Get list of supported actions.
     */
    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> getSupportedActions() {
        return ResponseEntity.ok(Map.of(
                "supportedActionTypes", List.of(
                        Map.of(
                                "type", "MEDIA_CONTROL",
                                "description", "Control system volume",
                                "parameters", List.of(
                                        Map.of("name", "deltaPercent", "type", "int", "default", "5"),
                                        Map.of("name", "direction", "type", "string", "values", List.of("+", "-")))),
                        Map.of(
                                "type", "OPEN_APP",
                                "description", "Launch application",
                                "parameters", List.of(
                                        Map.of("name", "appName", "type", "string", "required", true))),
                        Map.of(
                                "type", "HOTKEY",
                                "description", "Execute keyboard shortcut",
                                "parameters", List.of(
                                        Map.of("name", "keyCombination", "type", "string", "example", "Alt+Tab"))),
                        Map.of(
                                "type", "SYSTEM_COMMAND",
                                "description", "Execute system command",
                                "parameters", List.of(
                                        Map.of("name", "command", "type", "string", "values", List.of("timer", "cancel_timer")),
                                        Map.of("name", "args", "type", "string", "description",
                                                "Command arguments"))))));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String error, String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", error,
                "message", message,
                "timestamp", LocalDateTime.now().toString()));
    }

    public record ActionRequest(String actionType, Map<String, String> parameters) {
    }
}
