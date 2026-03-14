package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.exception.UnsupportedDisplayServerException;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.MouseClickRequest;
import org.jarvis.pccontrol.model.MouseMoveRequest;
import org.jarvis.pccontrol.model.ScrollRequest;
import org.jarvis.pccontrol.model.SendKeysRequest;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.CommandResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LinuxInputControl {

    private static final int MAX_KEYS_LENGTH = 200;
    private static final int MAX_SCROLL_AMOUNT = 100;
    private static final int MAX_BUTTON = 5;

    private static final Pattern SAFE_KEYS = Pattern.compile(
            "^[a-zA-Z0-9+_ ]+$");

    private static final Set<String> VALID_SCROLL_DIRECTIONS = Set.of("up", "down", "left", "right");

    private static final Map<String, String> SCROLL_BUTTON = Map.of(
            "up", "4",
            "down", "5",
            "left", "6",
            "right", "7");

    private final CommandExecutor commandExecutor;
    private final CommandLocator commandLocator;
    private final Supplier<String> displayServerDetector;

    @Autowired
    public LinuxInputControl(CommandExecutor commandExecutor, CommandLocator commandLocator) {
        this(commandExecutor, commandLocator, LinuxInputControl::detectDisplayServer);
    }

    LinuxInputControl(CommandExecutor commandExecutor, CommandLocator commandLocator,
                       Supplier<String> displayServerDetector) {
        this.commandExecutor = commandExecutor;
        this.commandLocator = commandLocator;
        this.displayServerDetector = displayServerDetector;
    }

    public DesktopOperationResponse sendKeys(SendKeysRequest request) throws IOException, InterruptedException {
        if (request == null || request.keys() == null || request.keys().isBlank()) {
            throw new IllegalArgumentException("Keys string is required");
        }
        String keys = request.keys().trim();
        if (keys.length() > MAX_KEYS_LENGTH) {
            throw new IllegalArgumentException("Keys string exceeds maximum length of " + MAX_KEYS_LENGTH);
        }
        if (!SAFE_KEYS.matcher(keys).matches()) {
            throw new IllegalArgumentException("Keys string contains unsafe characters");
        }
        requireXdotool();

        List<String> command = new ArrayList<>();
        command.add("xdotool");
        command.add("key");
        if (request.windowId() != null && !request.windowId().isBlank()) {
            LinuxWindowControl.validateWindowId(request.windowId());
            command.add("--window");
            command.add(request.windowId());
        }
        command.add(keys);

        CommandResult result = commandExecutor.execute(command);
        boolean success = result.exitCode() == 0;
        log.info("xdotool key result: exitCode={}, keys={}", result.exitCode(), keys);
        return new DesktopOperationResponse(
                success,
                "send_keys",
                success ? "Keys sent" : "Failed to send keys: " + result.stdout(),
                Map.of("keys", keys),
                null);
    }

    public DesktopOperationResponse mouseClick(MouseClickRequest request) throws IOException, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("Mouse click request is required");
        }
        validateCoordinates(request.x(), request.y());
        int button = request.button();
        if (button < 1 || button > MAX_BUTTON) {
            throw new IllegalArgumentException("Mouse button must be between 1 and " + MAX_BUTTON);
        }
        requireXdotool();

        CommandResult result = commandExecutor.execute(List.of(
                "xdotool", "mousemove", String.valueOf(request.x()), String.valueOf(request.y()),
                "click", String.valueOf(button)));
        boolean success = result.exitCode() == 0;
        return new DesktopOperationResponse(
                success,
                "mouse_click",
                success ? "Mouse click performed" : "Failed to click: " + result.stdout(),
                Map.of("x", request.x(), "y", request.y(), "button", button),
                null);
    }

    public DesktopOperationResponse mouseMove(MouseMoveRequest request) throws IOException, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("Mouse move request is required");
        }
        validateCoordinates(request.x(), request.y());
        requireXdotool();

        CommandResult result = commandExecutor.execute(List.of(
                "xdotool", "mousemove", String.valueOf(request.x()), String.valueOf(request.y())));
        boolean success = result.exitCode() == 0;
        return new DesktopOperationResponse(
                success,
                "mouse_move",
                success ? "Mouse moved" : "Failed to move mouse: " + result.stdout(),
                Map.of("x", request.x(), "y", request.y()),
                null);
    }

    public DesktopOperationResponse scroll(ScrollRequest request) throws IOException, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("Scroll request is required");
        }
        String direction = request.direction();
        if (!VALID_SCROLL_DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException(
                    "Invalid scroll direction: " + direction + ". Must be one of: " + VALID_SCROLL_DIRECTIONS);
        }
        if (request.amount() < 1 || request.amount() > MAX_SCROLL_AMOUNT) {
            throw new IllegalArgumentException("Scroll amount must be between 1 and " + MAX_SCROLL_AMOUNT);
        }
        requireXdotool();

        String scrollButton = SCROLL_BUTTON.get(direction);
        CommandResult result = commandExecutor.execute(List.of(
                "xdotool", "click", "--repeat", String.valueOf(request.amount()), scrollButton));
        boolean success = result.exitCode() == 0;
        return new DesktopOperationResponse(
                success,
                "scroll",
                success ? "Scroll performed" : "Failed to scroll: " + result.stdout(),
                Map.of("direction", direction, "amount", request.amount()),
                null);
    }

    private void requireXdotool() {
        String ds = displayServerDetector.get();
        if ("headless".equals(ds)) {
            throw new UnsupportedDisplayServerException("headless", "x11");
        }
        if (!commandLocator.isAvailable("xdotool")) {
            throw new MissingToolException("xdotool");
        }
    }

    static void validateCoordinates(int x, int y) {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Coordinates must be non-negative (x=" + x + ", y=" + y + ")");
        }
    }

    private static String detectDisplayServer() {
        if (System.getenv("WAYLAND_DISPLAY") != null) return "wayland";
        if (System.getenv("DISPLAY") != null) return "x11";
        return "headless";
    }
}
