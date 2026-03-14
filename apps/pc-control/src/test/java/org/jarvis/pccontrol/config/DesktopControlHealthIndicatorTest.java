package org.jarvis.pccontrol.config;

import org.jarvis.pccontrol.model.DesktopCapabilities;
import org.jarvis.pccontrol.service.CapabilityDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DesktopControlHealthIndicatorTest {

    @Mock
    private CapabilityDetector capabilityDetector;

    @InjectMocks
    private DesktopControlHealthIndicator indicator;

    @Test
    void reportsReadyWhenAllOperationsSupported() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "x11",
                Map.of("xdg-open", true, "xdotool", true, "wmctrl", true,
                        "xprop", true, "wpctl", true, "pactl", false, "amixer", false),
                Map.of("openFileSupported", true, "openUrlSupported", true,
                        "openAppSupported", true, "windowControlSupported", true,
                        "inputControlSupported", true, "audioControlSupported", true),
                Map.of("xwaylandOnly", false, "windowControlDegraded", false,
                        "inputControlDegraded", false));
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("x11", health.getDetails().get("displayServer"));
        assertEquals("READY", health.getDetails().get("readiness"));
        assertNull(health.getDetails().get("limitation"));
    }

    @Test
    void reportsDegradedWhenSomeOperationsUnsupported() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "x11",
                Map.of("xdg-open", true, "xdotool", false, "wmctrl", false,
                        "xprop", false, "wpctl", true, "pactl", false, "amixer", false),
                Map.of("openFileSupported", true, "openUrlSupported", true,
                        "openAppSupported", true, "windowControlSupported", false,
                        "inputControlSupported", false, "audioControlSupported", true),
                Map.of("xwaylandOnly", false, "windowControlDegraded", false,
                        "inputControlDegraded", true));
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("DEGRADED", health.getDetails().get("readiness"));
        String limitation = (String) health.getDetails().get("limitation");
        assertNotNull(limitation);
    }

    @Test
    void reportsLimitedOnHeadless() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "headless",
                Map.of("xdg-open", false, "xdotool", false, "wmctrl", false,
                        "xprop", false, "wpctl", false, "pactl", false, "amixer", false),
                Map.of("openFileSupported", false, "openUrlSupported", false,
                        "openAppSupported", false, "windowControlSupported", false,
                        "inputControlSupported", false, "audioControlSupported", false),
                Map.of("xwaylandOnly", false, "windowControlDegraded", false,
                        "inputControlDegraded", true));
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("UNAVAILABLE", health.getDetails().get("readiness"));
        String limitation = (String) health.getDetails().get("limitation");
        assertNotNull(limitation);
    }

    @Test
    void reportsLimitedOnHeadlessWithAudio() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "headless",
                Map.of("xdg-open", false, "xdotool", false, "wmctrl", false,
                        "xprop", false, "wpctl", true, "pactl", false, "amixer", false),
                Map.of("openFileSupported", false, "openUrlSupported", false,
                        "openAppSupported", false, "windowControlSupported", false,
                        "inputControlSupported", false, "audioControlSupported", true),
                Map.of("xwaylandOnly", false, "windowControlDegraded", false,
                        "inputControlDegraded", true));
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("LIMITED", health.getDetails().get("readiness"));
    }

    @Test
    void reportsDegradedOnWaylandWithXwayland() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "wayland",
                Map.of("xdg-open", true, "xdotool", true, "wmctrl", false,
                        "xprop", false, "wpctl", true, "pactl", false, "amixer", false),
                Map.of("openFileSupported", true, "openUrlSupported", true,
                        "openAppSupported", true, "windowControlSupported", true,
                        "inputControlSupported", true, "audioControlSupported", true),
                Map.of("xwaylandOnly", true, "windowControlDegraded", false,
                        "inputControlDegraded", false));
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("READY", health.getDetails().get("readiness"));
        String limitation = (String) health.getDetails().get("limitation");
        assertNotNull(limitation, "Wayland XWayland-only should report a limitation");
    }

    @Test
    void reportsDownWhenDetectionFails() {
        when(capabilityDetector.detect()).thenThrow(new RuntimeException("detection failed"));

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("detection failed", health.getDetails().get("error"));
    }

    @Test
    void includesToolMapInDetails() {
        Map<String, Boolean> tools = Map.of("xdg-open", true, "xdotool", false, "wmctrl", true,
                "xprop", false, "wpctl", true, "pactl", false, "amixer", false);
        DesktopCapabilities caps = new DesktopCapabilities("x11", tools,
                Map.of("openFileSupported", true, "openUrlSupported", true,
                        "openAppSupported", true, "windowControlSupported", true,
                        "inputControlSupported", false, "audioControlSupported", true),
                Map.of());
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        assertEquals(tools, health.getDetails().get("tools"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void includesOperationSupportInDetails() {
        Map<String, Boolean> ops = Map.of(
                "openFileSupported", true, "openUrlSupported", true,
                "openAppSupported", true, "windowControlSupported", false,
                "inputControlSupported", false, "audioControlSupported", true);
        DesktopCapabilities caps = new DesktopCapabilities("x11",
                Map.of("xdg-open", true, "xdotool", false, "wmctrl", false,
                        "xprop", false, "wpctl", true, "pactl", false, "amixer", false),
                ops, Map.of());
        when(capabilityDetector.detect()).thenReturn(caps);

        Health health = indicator.health();

        Map<String, Boolean> reported = (Map<String, Boolean>) health.getDetails().get("operationSupport");
        assertEquals(ops, reported);
    }
}
