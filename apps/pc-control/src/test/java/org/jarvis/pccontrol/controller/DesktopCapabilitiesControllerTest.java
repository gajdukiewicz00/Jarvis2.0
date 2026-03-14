package org.jarvis.pccontrol.controller;

import org.jarvis.pccontrol.model.DesktopCapabilities;
import org.jarvis.pccontrol.service.CapabilityDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DesktopCapabilitiesControllerTest {

    @Mock
    private CapabilityDetector capabilityDetector;

    @InjectMocks
    private DesktopCapabilitiesController controller;

    @Test
    void shouldReturnCapabilitiesWithOkStatus() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "x11",
                Map.of("xdotool", true, "pactl", true),
                Map.of("windowControlSupported", true),
                Map.of("xwaylandOnly", false)
        );
        when(capabilityDetector.detect()).thenReturn(caps);

        ResponseEntity<DesktopCapabilities> response = controller.getCapabilities();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("x11", response.getBody().displayServer());
    }

    @Test
    void shouldIncludeDisplayServerInResponse() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "wayland",
                Map.of(),
                Map.of(),
                Map.of("xwaylandOnly", true)
        );
        when(capabilityDetector.detect()).thenReturn(caps);

        ResponseEntity<DesktopCapabilities> response = controller.getCapabilities();

        assertEquals("wayland", response.getBody().displayServer());
    }

    @Test
    void shouldIncludeToolAvailabilityInResponse() {
        Map<String, Boolean> tools = Map.of("xdotool", false, "wmctrl", true);
        DesktopCapabilities caps = new DesktopCapabilities("x11", tools, Map.of(), Map.of());
        when(capabilityDetector.detect()).thenReturn(caps);

        ResponseEntity<DesktopCapabilities> response = controller.getCapabilities();

        assertFalse(response.getBody().runtimeTools().get("xdotool"));
        assertTrue(response.getBody().runtimeTools().get("wmctrl"));
    }

    @Test
    void shouldIncludeOperationSupportFlags() {
        Map<String, Boolean> ops = Map.of(
                "openFileSupported", true,
                "windowControlSupported", false,
                "audioControlSupported", true
        );
        DesktopCapabilities caps = new DesktopCapabilities("x11", Map.of(), ops, Map.of());
        when(capabilityDetector.detect()).thenReturn(caps);

        ResponseEntity<DesktopCapabilities> response = controller.getCapabilities();

        assertTrue(response.getBody().operationSupport().get("openFileSupported"));
        assertFalse(response.getBody().operationSupport().get("windowControlSupported"));
        assertTrue(response.getBody().operationSupport().get("audioControlSupported"));
    }

    @Test
    void shouldIncludeDegradedFlags() {
        Map<String, Boolean> degraded = Map.of(
                "xwaylandOnly", true,
                "inputControlDegraded", false
        );
        DesktopCapabilities caps = new DesktopCapabilities("wayland", Map.of(), Map.of(), degraded);
        when(capabilityDetector.detect()).thenReturn(caps);

        ResponseEntity<DesktopCapabilities> response = controller.getCapabilities();

        assertTrue(response.getBody().degraded().get("xwaylandOnly"));
        assertFalse(response.getBody().degraded().get("inputControlDegraded"));
    }

    @Test
    void shouldReturnHeadlessCapabilities() {
        DesktopCapabilities caps = new DesktopCapabilities(
                "headless",
                Map.of("xdotool", false),
                Map.of("windowControlSupported", false, "audioControlSupported", false),
                Map.of("inputControlDegraded", true)
        );
        when(capabilityDetector.detect()).thenReturn(caps);

        ResponseEntity<DesktopCapabilities> response = controller.getCapabilities();

        assertEquals("headless", response.getBody().displayServer());
        assertFalse(response.getBody().operationSupport().get("windowControlSupported"));
    }
}
