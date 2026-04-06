package org.jarvis.pccontrol.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;
import org.jarvis.pccontrol.model.PcActionStepResult;
import org.jarvis.pccontrol.model.PcScenarioDefinition;
import org.jarvis.pccontrol.model.PcScenarioStep;
import org.jarvis.pccontrol.security.CommandValidator;
import org.jarvis.pccontrol.service.PcActionExecutionService;
import org.jarvis.pccontrol.service.PcScenarioRegistry;
import org.jarvis.pccontrol.service.SystemControlService;
import org.jarvis.pccontrol.service.TimerLimitExceededException;
import org.jarvis.pccontrol.service.TimerSchedulerService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPcActionExecutionService implements PcActionExecutionService {

    private final SystemControlService systemControlService;
    private final TimerSchedulerService timerSchedulerService;
    private final CommandValidator commandValidator;
    private final PcScenarioRegistry scenarioRegistry;

    @Override
    public PcActionResult execute(PcActionRequest request) {
        String actionType = normalizeActionType(request.actionType());
        Map<String, String> parameters = request.parameters();
        log.info("Executing PC action type={}, parameters={}", actionType, parameters);

        if (actionType == null) {
            return result(false, null, PcActionExecutionStatus.REJECTED,
                    "Action type is required", "INVALID_ACTION_TYPE", Map.of(), List.of());
        }

        try {
            commandValidator.validateAction(actionType);
        } catch (ResponseStatusException e) {
            String errorCode = e.getStatusCode().is4xxClientError() ? "BLOCKED_ACTION" : "INVALID_ACTION_TYPE";
            return result(false, actionType, PcActionExecutionStatus.REJECTED, e.getReason(), errorCode, Map.of(), List.of());
        }

        return switch (actionType) {
            case "MEDIA_CONTROL" -> handleMediaControl(parameters);
            case "VOLUME_UP" -> executeSingleStep(actionType, primitiveStep("volume-up", actionType, parameters));
            case "VOLUME_DOWN" -> executeSingleStep(actionType, primitiveStep("volume-down", actionType, parameters));
            case "SET_VOLUME", "VOLUME_SET" -> executeSingleStep("SET_VOLUME",
                    primitiveStep("set-volume", "SET_VOLUME", parameters));
            case "MUTE" -> executeSingleStep(actionType, primitiveStep("mute", actionType, parameters));
            case "UNMUTE" -> executeSingleStep(actionType, primitiveStep("unmute", actionType, parameters));
            case "PLAY_PAUSE" -> executeSingleStep(actionType, primitiveStep("play-pause", actionType, parameters));
            case "PAUSE" -> executeSingleStep(actionType, primitiveStep("pause", actionType, parameters));
            case "NEXT" -> executeSingleStep(actionType, primitiveStep("next", actionType, parameters));
            case "PREV" -> executeSingleStep(actionType, primitiveStep("prev", actionType, parameters));
            case "OPEN_APP" -> executeSingleStep(actionType, primitiveStep("open-app", actionType, parameters));
            case "OPEN_URL" -> executeSingleStep(actionType, primitiveStep("open-url", actionType, parameters));
            case "HOTKEY" -> executeSingleStep(actionType, primitiveStep("hotkey", actionType, parameters));
            case "NOTIFY" -> executeSingleStep(actionType, primitiveStep("notify", actionType, parameters));
            case "SYSTEM_COMMAND" -> handleSystemCommand(parameters);
            case "SCENARIO" -> handleScenario(parameters);
            default -> result(false, actionType, PcActionExecutionStatus.REJECTED,
                    "Unknown action type: " + actionType, "UNKNOWN_ACTION_TYPE", Map.of(), List.of());
        };
    }

    private PcActionResult handleMediaControl(Map<String, String> parameters) {
        String mediaAction = firstNonBlank(parameters, "action", "mediaAction");
        if (mediaAction != null) {
            String normalized = normalizeActionType(mediaAction);
            return execute(new PcActionRequest(normalized, parameters));
        }
        int delta = boundedInt(parameters.getOrDefault("deltaPercent", "5"), 1, 100, "deltaPercent");
        String direction = "+".equals(parameters.getOrDefault("direction", "+")) ? "+" : "-";
        PcActionStepResult step = executeStep("media-volume", "MEDIA_CONTROL", Map.of(
                "deltaPercent", String.valueOf(delta),
                "direction", direction
        ), () -> systemControlService.changeVolume(delta, direction),
                "Adjusted media volume by " + direction + delta + "%", Map.of(
                        "delta", delta,
                        "direction", direction));
        return executeSingleStep("MEDIA_CONTROL", step);
    }

    private PcActionResult handleScenario(Map<String, String> parameters) {
        String scenarioName = firstNonBlank(parameters, "name", "scenario");
        if (scenarioName == null) {
            return result(false, "SCENARIO", PcActionExecutionStatus.REJECTED,
                    "Scenario name is required", "INVALID_PARAMETER", Map.of(), List.of());
        }

        PcScenarioDefinition definition = scenarioRegistry.findByName(scenarioName)
                .orElse(null);
        if (definition == null) {
            return result(false, "SCENARIO", PcActionExecutionStatus.REJECTED,
                    "Unknown scenario: " + scenarioName, "UNKNOWN_SCENARIO",
                    Map.of("scenario", scenarioName), List.of());
        }

        List<PcActionStepResult> steps = new ArrayList<>();
        for (PcScenarioStep step : definition.steps()) {
            steps.add(primitiveStep(step.id(), step.actionType(), step.parameters()));
        }

        long failures = steps.stream().filter(step -> step.status() == PcActionExecutionStatus.FAILED).count();
        long successes = steps.stream().filter(step -> step.status().isSuccessful()).count();
        PcActionExecutionStatus status = failures == 0
                ? PcActionExecutionStatus.SUCCESS
                : successes > 0 ? PcActionExecutionStatus.PARTIAL_SUCCESS : PcActionExecutionStatus.FAILED;
        String message = switch (status) {
            case SUCCESS -> "Scenario '" + definition.name() + "' executed successfully";
            case PARTIAL_SUCCESS -> "Scenario '" + definition.name() + "' completed with partial success";
            case FAILED -> "Scenario '" + definition.name() + "' failed";
            case REJECTED -> "Scenario '" + definition.name() + "' was rejected";
        };

        return result(status.isSuccessful(), "SCENARIO", status, message, null, Map.of(
                "scenario", definition.name(),
                "description", definition.description(),
                "stepCount", definition.steps().size(),
                "successfulSteps", successes,
                "failedSteps", failures
        ), steps);
    }

    private PcActionResult handleSystemCommand(Map<String, String> parameters) {
        String command = firstNonBlank(parameters, "command");
        if (command == null) {
            return result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    "Parameter 'command' is required", "INVALID_PARAMETER", Map.of(), List.of());
        }

        return switch (command.trim().toLowerCase(Locale.ROOT)) {
            case "timer" -> scheduleTimer(parameters);
            case "cancel_timer" -> cancelTimer(parameters);
            case "sleep" -> executeSingleStep("SYSTEM_COMMAND",
                    executeStep("sleep", "SYSTEM_COMMAND", Map.of("command", "sleep"),
                            systemControlService::sleep, "Sleep mode requested", Map.of("command", "sleep")));
            case "monitor_off" -> executeSingleStep("SYSTEM_COMMAND",
                    executeStep("monitor-off", "SYSTEM_COMMAND", Map.of("command", "monitor_off"),
                            systemControlService::turnMonitorOff, "Monitor off requested", Map.of("command", "monitor_off")));
            default -> result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    "Unknown system command: " + command, "UNKNOWN_COMMAND",
                    Map.of("command", command), List.of());
        };
    }

    private PcActionResult scheduleTimer(Map<String, String> parameters) {
        String raw = firstNonBlank(parameters, "args", "seconds", "durationSeconds");
        if (raw == null) {
            return result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    "Timer duration is required", "INVALID_PARAMETER", Map.of("command", "timer"), List.of());
        }

        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    "Timer duration is required", "INVALID_PARAMETER", Map.of("command", "timer"), List.of());
        }

        int seconds = boundedInt(digits, 1, 86400, "durationSeconds");
        try {
            String timerId = timerSchedulerService.scheduleTimer(seconds, () -> {
                try {
                    systemControlService.sendNotification("Timer", "Time is up!");
                    systemControlService.beep();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.error("Timer callback failed: {}", e.getMessage(), e);
                }
            });
            return result(true, "SYSTEM_COMMAND", PcActionExecutionStatus.SUCCESS,
                    "Timer started for " + seconds + " seconds", null, Map.of(
                            "command", "timer",
                            "timerId", timerId,
                            "durationSeconds", seconds), List.of());
        } catch (TimerLimitExceededException e) {
            return result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    e.getMessage(), "TIMER_LIMIT_EXCEEDED", Map.of("command", "timer"), List.of());
        }
    }

    private PcActionResult cancelTimer(Map<String, String> parameters) {
        String timerId = firstNonBlank(parameters, "timerId", "args");
        if (timerId == null) {
            return result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    "Parameter 'timerId' is required", "INVALID_PARAMETER", Map.of("command", "cancel_timer"), List.of());
        }

        boolean cancelled = timerSchedulerService.cancelTimer(timerId);
        if (!cancelled) {
            return result(false, "SYSTEM_COMMAND", PcActionExecutionStatus.REJECTED,
                    "Timer not found: " + timerId, "TIMER_NOT_FOUND",
                    Map.of("command", "cancel_timer", "timerId", timerId), List.of());
        }

        return result(true, "SYSTEM_COMMAND", PcActionExecutionStatus.SUCCESS,
                "Timer cancelled", null, Map.of(
                        "command", "cancel_timer",
                        "timerId", timerId), List.of());
    }

    private PcActionResult executeSingleStep(String actionType, PcActionStepResult step) {
        PcActionExecutionStatus status = step.status();
        return result(status.isSuccessful(), actionType, status, step.message(),
                status == PcActionExecutionStatus.FAILED ? "EXECUTION_FAILED" : null,
                step.details(), List.of(step));
    }

    private PcActionStepResult primitiveStep(String stepId, String actionType, Map<String, String> parameters) {
        return switch (normalizeActionType(actionType)) {
            case "VOLUME_UP" -> {
                int delta = boundedInt(parameters.getOrDefault("delta", "10"), 1, 100, "delta");
                yield executeStep(stepId, "VOLUME_UP", parameters, () -> systemControlService.changeVolume(delta, "+"),
                        "Volume increased by " + delta + "%", Map.of("delta", delta, "direction", "+"));
            }
            case "VOLUME_DOWN" -> {
                int delta = boundedInt(parameters.getOrDefault("delta", "10"), 1, 100, "delta");
                yield executeStep(stepId, "VOLUME_DOWN", parameters, () -> systemControlService.changeVolume(delta, "-"),
                        "Volume decreased by " + delta + "%", Map.of("delta", delta, "direction", "-"));
            }
            case "SET_VOLUME", "VOLUME_SET" -> {
                int level = boundedInt(parameters.getOrDefault("level", "100"), 0, 100, "level");
                yield executeStep(stepId, "SET_VOLUME", parameters, () -> systemControlService.setVolume(level),
                        "Volume set to " + level + "%", Map.of("level", level));
            }
            case "MUTE" -> executeStep(stepId, "MUTE", parameters, systemControlService::mute,
                    "Sound muted", Map.of());
            case "UNMUTE" -> executeStep(stepId, "UNMUTE", parameters, systemControlService::unmute,
                    "Sound unmuted", Map.of());
            case "PLAY_PAUSE" -> executeStep(stepId, "PLAY_PAUSE", parameters, systemControlService::playPause,
                    "Playback toggled", Map.of());
            case "PAUSE" -> executeStep(stepId, "PAUSE", parameters, systemControlService::pause,
                    "Playback paused", Map.of());
            case "NEXT" -> executeStep(stepId, "NEXT", parameters, systemControlService::next,
                    "Skipped to next track", Map.of());
            case "PREV" -> executeStep(stepId, "PREV", parameters, systemControlService::prev,
                    "Returned to previous track", Map.of());
            case "OPEN_APP" -> {
                String app = firstNonBlank(parameters, "app", "appName");
                if (app == null) {
                    yield rejectedStep(stepId, "OPEN_APP", "Parameter 'app' is required");
                }
                yield executeStep(stepId, "OPEN_APP", parameters, () -> systemControlService.openApp(app),
                        "Application launch initiated", Map.of("app", app));
            }
            case "OPEN_URL" -> {
                String url = firstNonBlank(parameters, "url");
                if (url == null) {
                    yield rejectedStep(stepId, "OPEN_URL", "Parameter 'url' is required");
                }
                yield executeStep(stepId, "OPEN_URL", parameters, () -> systemControlService.openUrl(url),
                        "URL launch initiated", Map.of("url", url));
            }
            case "HOTKEY" -> {
                String hotkey = firstNonBlank(parameters, "keyCombination", "keys", "combination");
                if (hotkey == null) {
                    yield rejectedStep(stepId, "HOTKEY", "Parameter 'keyCombination' is required");
                }
                yield executeStep(stepId, "HOTKEY", parameters, () -> systemControlService.executeHotkey(hotkey),
                        "Hotkey executed", Map.of("keyCombination", hotkey));
            }
            case "WAIT" -> {
                int millis = boundedInt(parameters.getOrDefault("millis", parameters.getOrDefault("ms", "0")),
                        0, 60_000, "millis");
                yield executeStep(stepId, "WAIT", parameters, () -> Thread.sleep(millis),
                        "Waited for " + millis + "ms", Map.of("millis", millis));
            }
            case "WINDOW_FOCUS" -> {
                String title = firstNonBlank(parameters, "title", "windowTitle");
                if (title == null) {
                    yield rejectedStep(stepId, "WINDOW_FOCUS", "Parameter 'title' is required");
                }
                yield executeStep(stepId, "WINDOW_FOCUS", parameters, () -> systemControlService.focusWindow(title),
                        "Window focused", Map.of("title", title));
            }
            case "WINDOW_CLOSE" -> {
                String title = firstNonBlank(parameters, "title", "windowTitle");
                yield executeStep(stepId, "WINDOW_CLOSE", parameters, () -> systemControlService.closeWindow(title),
                        "Window closed", title == null ? Map.of() : Map.of("title", title));
            }
            case "WINDOW_MINIMIZE" -> {
                String title = firstNonBlank(parameters, "title", "windowTitle");
                yield executeStep(stepId, "WINDOW_MINIMIZE", parameters, () -> systemControlService.minimizeWindow(title),
                        "Window minimized", title == null ? Map.of() : Map.of("title", title));
            }
            case "WINDOW_MAXIMIZE" -> {
                String title = firstNonBlank(parameters, "title", "windowTitle");
                yield executeStep(stepId, "WINDOW_MAXIMIZE", parameters, () -> systemControlService.maximizeWindow(title),
                        "Window maximized", title == null ? Map.of() : Map.of("title", title));
            }
            case "WINDOW_NORMALIZE", "WINDOW_RESTORE" -> {
                String title = firstNonBlank(parameters, "title", "windowTitle");
                yield executeStep(stepId, "WINDOW_NORMALIZE", parameters, () -> systemControlService.normalizeWindow(title),
                        "Window normalized", title == null ? Map.of() : Map.of("title", title));
            }
            case "MOUSE_MOVE" -> {
                int x = boundedInt(firstNonBlank(parameters, "x"), 0, 20_000, "x");
                int y = boundedInt(firstNonBlank(parameters, "y"), 0, 20_000, "y");
                yield executeStep(stepId, "MOUSE_MOVE", parameters, () -> systemControlService.moveMouseAbsolute(x, y),
                        "Mouse moved", Map.of("x", x, "y", y));
            }
            case "MOUSE_LEFT_CLICK" -> executeStep(stepId, "MOUSE_LEFT_CLICK", parameters,
                    systemControlService::leftClick, "Left click executed", Map.of());
            case "MOUSE_RIGHT_CLICK" -> executeStep(stepId, "MOUSE_RIGHT_CLICK", parameters,
                    systemControlService::rightClick, "Right click executed", Map.of());
            case "MOUSE_LEFT_DOWN" -> executeStep(stepId, "MOUSE_LEFT_DOWN", parameters,
                    systemControlService::leftButtonDown, "Left button down", Map.of());
            case "MOUSE_LEFT_UP" -> executeStep(stepId, "MOUSE_LEFT_UP", parameters,
                    systemControlService::leftButtonUp, "Left button up", Map.of());
            case "EMPTY_TRASH" -> executeStep(stepId, "EMPTY_TRASH", parameters,
                    systemControlService::emptyTrash, "Trash emptied", Map.of());
            case "OPEN_OPTICAL_DRIVE" -> executeStep(stepId, "OPEN_OPTICAL_DRIVE", parameters,
                    systemControlService::openOpticalDrive, "Optical drive opened", Map.of());
            case "CLOSE_OPTICAL_DRIVE" -> executeStep(stepId, "CLOSE_OPTICAL_DRIVE", parameters,
                    systemControlService::closeOpticalDrive, "Optical drive closed", Map.of());
            case "NOTIFY" -> {
                String title = parameters.getOrDefault("title", "Jarvis");
                String message = parameters.getOrDefault("message", "Notification");
                yield executeStep(stepId, "NOTIFY", parameters, () -> systemControlService.sendNotification(title, message),
                        "Notification sent", Map.of("title", title, "message", message));
            }
            default -> rejectedStep(stepId, actionType, "Action is not supported inside scenario: " + actionType);
        };
    }

    private PcActionStepResult executeStep(String stepId, String actionType, Map<String, String> parameters,
                                           ThrowingAction action, String successMessage, Map<String, Object> details) {
        try {
            action.run();
            return new PcActionStepResult(stepId, actionType, PcActionExecutionStatus.SUCCESS, successMessage, details);
        } catch (IllegalArgumentException e) {
            return rejectedStep(stepId, actionType, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedStep(stepId, actionType, "Execution interrupted: " + e.getMessage(), details);
        } catch (IOException | RuntimeException e) {
            return failedStep(stepId, actionType, "Execution failed: " + e.getMessage(), details);
        }
    }

    private PcActionStepResult rejectedStep(String stepId, String actionType, String message) {
        return new PcActionStepResult(stepId, actionType, PcActionExecutionStatus.REJECTED, message, Map.of());
    }

    private PcActionStepResult failedStep(String stepId, String actionType, String message, Map<String, Object> details) {
        return new PcActionStepResult(stepId, actionType, PcActionExecutionStatus.FAILED, message, details);
    }

    private PcActionResult result(boolean success, String actionType, PcActionExecutionStatus status,
                                  String message, String errorCode, Map<String, Object> details,
                                  List<PcActionStepResult> steps) {
        return new PcActionResult(success, actionType, status, message, errorCode, details, steps, Instant.now());
    }

    private static String firstNonBlank(Map<String, String> parameters, String... keys) {
        for (String key : keys) {
            String value = parameters.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static int boundedInt(String raw, int min, int max, String fieldName) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + fieldName + "' must be numeric");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "Parameter '" + fieldName + "' must be between " + min + " and " + max);
        }
        return value;
    }

    private static String normalizeActionType(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return null;
        }
        return actionType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws IOException, InterruptedException;
    }
}
