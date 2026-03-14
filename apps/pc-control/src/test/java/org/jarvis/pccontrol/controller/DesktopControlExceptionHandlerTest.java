package org.jarvis.pccontrol.controller;

import org.jarvis.pccontrol.exception.DesktopControlException;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.exception.UnsupportedDisplayServerException;
import org.jarvis.pccontrol.exception.WindowNotFoundException;
import org.jarvis.pccontrol.model.DesktopErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DesktopControlExceptionHandlerTest {

    private DesktopControlExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DesktopControlExceptionHandler();
    }

    @Test
    void handlesMissingToolException() {
        MissingToolException ex = new MissingToolException("xdotool");
        ResponseEntity<DesktopErrorResponse> response = handler.handleMissingTool(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("MISSING_TOOL", response.getBody().code());
        assertEquals("Required tool not found: xdotool", response.getBody().message());
        assertEquals("xdotool", response.getBody().details().get("toolName"));
        assertNotNull(response.getBody().timestamp());
    }

    @Test
    void handlesUnsupportedDisplayServerException() {
        UnsupportedDisplayServerException ex = new UnsupportedDisplayServerException("wayland", "x11");
        ResponseEntity<DesktopErrorResponse> response = handler.handleUnsupportedDisplay(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UNSUPPORTED_DISPLAY_SERVER", response.getBody().code());
        assertEquals("wayland", response.getBody().details().get("detected"));
        assertEquals("x11", response.getBody().details().get("required"));
    }

    @Test
    void handlesWindowNotFoundException() {
        WindowNotFoundException ex = new WindowNotFoundException("Firefox");
        ResponseEntity<DesktopErrorResponse> response = handler.handleWindowNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("WINDOW_NOT_FOUND", response.getBody().code());
        assertEquals("Window not found: Firefox", response.getBody().message());
        assertEquals("Firefox", response.getBody().details().get("windowIdentifier"));
    }

    @Test
    void handlesIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid input");
        ResponseEntity<DesktopErrorResponse> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().code());
        assertEquals("Invalid input", response.getBody().message());
    }

    @Test
    void handlesGenericDesktopControlException() {
        DesktopControlException ex = new DesktopControlException("General error");
        ResponseEntity<DesktopErrorResponse> response = handler.handleDesktopControlException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("DESKTOP_CONTROL_ERROR", response.getBody().code());
        assertEquals("General error", response.getBody().message());
    }

    @Test
    void handlesIllegalStateException() {
        IllegalStateException ex = new IllegalStateException("Service down");
        ResponseEntity<DesktopErrorResponse> response = handler.handleUnavailable(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DESKTOP_UNAVAILABLE", response.getBody().code());
    }

    @Test
    void handlesIOException() {
        java.io.IOException ex = new java.io.IOException("Disk error");
        ResponseEntity<DesktopErrorResponse> response = handler.handleIo(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("DESKTOP_IO_ERROR", response.getBody().code());
    }

    @Test
    void handlesInterruptedException() {
        InterruptedException ex = new InterruptedException("Thread interrupted");
        ResponseEntity<DesktopErrorResponse> response = handler.handleInterrupted(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("DESKTOP_INTERRUPTED", response.getBody().code());
    }
}
