package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.DesktopCapabilities;
import org.jarvis.pccontrol.service.CapabilityDetector;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Service
public class LinuxCapabilityDetector implements CapabilityDetector {

    private static final List<String> TOOLS = List.of(
            "xdg-open", "xdotool", "wmctrl", "xprop", "wpctl", "pactl", "amixer"
    );

    private final Function<String, String> envLookup;
    private final Function<String, Boolean> toolChecker;

    public LinuxCapabilityDetector() {
        this(System::getenv, LinuxCapabilityDetector::isToolInstalled);
    }

    LinuxCapabilityDetector(Function<String, String> envLookup, Function<String, Boolean> toolChecker) {
        this.envLookup = envLookup;
        this.toolChecker = toolChecker;
    }

    @Override
    public DesktopCapabilities detect() {
        String displayServer = detectDisplayServer();
        Map<String, Boolean> tools = detectTools();
        Map<String, Boolean> ops = deriveOperationSupport(displayServer, tools);
        Map<String, Boolean> degraded = deriveDegraded(displayServer, tools);

        log.info("Detected capabilities: display={}, tools={}, ops={}, degraded={}",
                displayServer, tools, ops, degraded);

        return new DesktopCapabilities(displayServer, tools, ops, degraded);
    }

    private String detectDisplayServer() {
        String sessionType = envLookup.apply("XDG_SESSION_TYPE");
        String waylandDisplay = envLookup.apply("WAYLAND_DISPLAY");
        String display = envLookup.apply("DISPLAY");

        if ("wayland".equalsIgnoreCase(sessionType) || waylandDisplay != null) {
            return "wayland";
        }
        if ("x11".equalsIgnoreCase(sessionType)) {
            return "x11";
        }
        if (display != null && !display.isBlank()) {
            return "x11";
        }
        return "headless";
    }

    private Map<String, Boolean> detectTools() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String tool : TOOLS) {
            result.put(tool, toolChecker.apply(tool));
        }
        return result;
    }

    private static Map<String, Boolean> deriveOperationSupport(String displayServer, Map<String, Boolean> tools) {
        boolean isHeadless = "headless".equals(displayServer);
        boolean hasXdgOpen = Boolean.TRUE.equals(tools.get("xdg-open"));
        boolean hasXdotool = Boolean.TRUE.equals(tools.get("xdotool"));
        boolean hasWmctrl = Boolean.TRUE.equals(tools.get("wmctrl"));
        boolean hasAudio = Boolean.TRUE.equals(tools.get("pactl"))
                || Boolean.TRUE.equals(tools.get("wpctl"))
                || Boolean.TRUE.equals(tools.get("amixer"));

        Map<String, Boolean> ops = new LinkedHashMap<>();
        ops.put("openFileSupported", !isHeadless && hasXdgOpen);
        ops.put("openUrlSupported", !isHeadless && hasXdgOpen);
        ops.put("openAppSupported", !isHeadless);
        ops.put("windowControlSupported", !isHeadless && (hasXdotool || hasWmctrl));
        ops.put("inputControlSupported", !isHeadless && hasXdotool);
        ops.put("audioControlSupported", hasAudio);
        return ops;
    }

    private static Map<String, Boolean> deriveDegraded(String displayServer, Map<String, Boolean> tools) {
        Map<String, Boolean> degraded = new LinkedHashMap<>();

        boolean isWayland = "wayland".equals(displayServer);
        boolean hasXdotool = Boolean.TRUE.equals(tools.get("xdotool"));
        boolean hasWmctrl = Boolean.TRUE.equals(tools.get("wmctrl"));

        degraded.put("xwaylandOnly", isWayland && hasXdotool);
        degraded.put("windowControlDegraded", isWayland && !hasXdotool && !hasWmctrl);
        degraded.put("inputControlDegraded", !hasXdotool);

        return degraded;
    }

    private static boolean isToolInstalled(String tool) {
        try {
            Process p = new ProcessBuilder("which", tool)
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
