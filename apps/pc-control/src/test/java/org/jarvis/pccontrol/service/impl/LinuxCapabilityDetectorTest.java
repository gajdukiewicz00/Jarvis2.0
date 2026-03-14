package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.DesktopCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LinuxCapabilityDetectorTest {

    @Test
    void shouldDetectX11WhenXdgSessionTypeIsX11() {
        LinuxCapabilityDetector detector = detectorWithEnv(
                Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0"));

        DesktopCapabilities caps = detector.detect();

        assertEquals("x11", caps.displayServer());
    }

    @Test
    void shouldDetectWaylandWhenXdgSessionTypeIsWayland() {
        LinuxCapabilityDetector detector = detectorWithEnv(
                Map.of("XDG_SESSION_TYPE", "wayland"));

        DesktopCapabilities caps = detector.detect();

        assertEquals("wayland", caps.displayServer());
    }

    @Test
    void shouldDetectWaylandWhenWaylandDisplaySet() {
        LinuxCapabilityDetector detector = detectorWithEnv(
                Map.of("WAYLAND_DISPLAY", "wayland-0"));

        DesktopCapabilities caps = detector.detect();

        assertEquals("wayland", caps.displayServer());
    }

    @Test
    void shouldDetectX11FromDisplayVariable() {
        LinuxCapabilityDetector detector = detectorWithEnv(Map.of("DISPLAY", ":0"));

        DesktopCapabilities caps = detector.detect();

        assertEquals("x11", caps.displayServer());
    }

    @Test
    void shouldDetectHeadlessWhenNoDisplayVars() {
        LinuxCapabilityDetector detector = detectorWithEnv(Map.of());

        DesktopCapabilities caps = detector.detect();

        assertEquals("headless", caps.displayServer());
    }

    @Test
    void shouldReportToolAvailableWhenPresent() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                k -> null,
                tool -> "xdotool".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.runtimeTools().get("xdotool"));
        assertFalse(caps.runtimeTools().get("wmctrl"));
    }

    @Test
    void shouldReportAllToolsMissing() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                k -> null,
                tool -> false
        );

        DesktopCapabilities caps = detector.detect();

        caps.runtimeTools().values().forEach(v -> assertFalse(v));
    }

    @Test
    void shouldContainAllExpectedTools() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(k -> null, tool -> false);

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.runtimeTools().containsKey("xdg-open"));
        assertTrue(caps.runtimeTools().containsKey("xdotool"));
        assertTrue(caps.runtimeTools().containsKey("wmctrl"));
        assertTrue(caps.runtimeTools().containsKey("xprop"));
        assertTrue(caps.runtimeTools().containsKey("wpctl"));
        assertTrue(caps.runtimeTools().containsKey("pactl"));
        assertTrue(caps.runtimeTools().containsKey("amixer"));
    }

    @Test
    void shouldDeriveWindowControlSupportedWhenXdotoolPresent() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> "xdotool".equals(tool) || "xdg-open".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.operationSupport().get("windowControlSupported"));
        assertTrue(caps.operationSupport().get("inputControlSupported"));
    }

    @Test
    void shouldDeriveWindowControlSupportedFromWmctrl() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> "wmctrl".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.operationSupport().get("windowControlSupported"));
        assertFalse(caps.operationSupport().get("inputControlSupported"));
    }

    @Test
    void shouldReportWindowControlUnsupportedWhenHeadless() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                k -> null,
                tool -> true
        );

        DesktopCapabilities caps = detector.detect();

        assertEquals("headless", caps.displayServer());
        assertFalse(caps.operationSupport().get("windowControlSupported"));
        assertFalse(caps.operationSupport().get("inputControlSupported"));
        assertFalse(caps.operationSupport().get("openFileSupported"));
        assertFalse(caps.operationSupport().get("openUrlSupported"));
        assertFalse(caps.operationSupport().get("openAppSupported"));
    }

    @Test
    void shouldReportAudioSupportedWhenPactlPresent() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                k -> null,
                tool -> "pactl".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.operationSupport().get("audioControlSupported"));
    }

    @Test
    void shouldReportAudioSupportedWhenWpctlPresent() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                k -> null,
                tool -> "wpctl".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.operationSupport().get("audioControlSupported"));
    }

    @Test
    void shouldReportAudioSupportedWhenAmixerPresent() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                k -> null,
                tool -> "amixer".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.operationSupport().get("audioControlSupported"));
    }

    @Test
    void shouldReportAudioUnsupportedWhenNoAudioTools() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> "xdotool".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertFalse(caps.operationSupport().get("audioControlSupported"));
    }

    @Test
    void shouldSetXwaylandDegradedWhenWaylandWithXdotool() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "wayland")),
                tool -> "xdotool".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.degraded().get("xwaylandOnly"));
    }

    @Test
    void shouldNotSetXwaylandDegradedOnX11() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> "xdotool".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertFalse(caps.degraded().get("xwaylandOnly"));
    }

    @Test
    void shouldSetWindowControlDegradedOnWaylandWithoutTools() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "wayland")),
                tool -> false
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.degraded().get("windowControlDegraded"));
    }

    @Test
    void shouldNotSetWindowControlDegradedWhenWaylandHasXdotool() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "wayland")),
                tool -> "xdotool".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertFalse(caps.degraded().get("windowControlDegraded"));
    }

    @Test
    void shouldSetInputDegradedWhenXdotoolMissing() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> false
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.degraded().get("inputControlDegraded"));
    }

    @Test
    void shouldNotSetInputDegradedWhenXdotoolPresent() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> "xdotool".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertFalse(caps.degraded().get("inputControlDegraded"));
    }

    @Test
    void shouldReturnOpenFileSupportedOnX11WithXdgOpen() {
        LinuxCapabilityDetector detector = new LinuxCapabilityDetector(
                envOf(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0")),
                tool -> "xdg-open".equals(tool)
        );

        DesktopCapabilities caps = detector.detect();

        assertTrue(caps.operationSupport().get("openFileSupported"));
        assertTrue(caps.operationSupport().get("openUrlSupported"));
    }

    private LinuxCapabilityDetector detectorWithEnv(Map<String, String> env) {
        return new LinuxCapabilityDetector(envOf(env), tool -> false);
    }

    private static Function<String, String> envOf(Map<String, String> env) {
        return env::get;
    }
}
