package org.jarvis.pccontrol.controller;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.exception.DesktopControlException;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.exception.UnsupportedDisplayServerException;
import org.jarvis.pccontrol.exception.WindowNotFoundException;
import org.jarvis.pccontrol.model.DesktopErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class DesktopControlExceptionHandler {

    @ExceptionHandler(MissingToolException.class)
    public ResponseEntity<DesktopErrorResponse> handleMissingTool(MissingToolException ex) {
        log.warn("Missing runtime tool: {}", ex.getToolName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new DesktopErrorResponse("MISSING_TOOL", ex.getMessage(),
                        Map.of("toolName", ex.getToolName()), null));
    }

    @ExceptionHandler(UnsupportedDisplayServerException.class)
    public ResponseEntity<DesktopErrorResponse> handleUnsupportedDisplay(UnsupportedDisplayServerException ex) {
        log.warn("Unsupported display server: detected={}, required={}", ex.getDetectedServer(), ex.getRequiredServer());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new DesktopErrorResponse("UNSUPPORTED_DISPLAY_SERVER", ex.getMessage(),
                        Map.of("detected", ex.getDetectedServer(), "required", ex.getRequiredServer()), null));
    }

    @ExceptionHandler(WindowNotFoundException.class)
    public ResponseEntity<DesktopErrorResponse> handleWindowNotFound(WindowNotFoundException ex) {
        log.warn("Window not found: {}", ex.getWindowIdentifier());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new DesktopErrorResponse("WINDOW_NOT_FOUND", ex.getMessage(),
                        Map.of("windowIdentifier", ex.getWindowIdentifier()), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<DesktopErrorResponse> handleValidation(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new DesktopErrorResponse("VALIDATION_ERROR", ex.getMessage(), null, null));
    }

    @ExceptionHandler(DesktopControlException.class)
    public ResponseEntity<DesktopErrorResponse> handleDesktopControlException(DesktopControlException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new DesktopErrorResponse("DESKTOP_CONTROL_ERROR", ex.getMessage(), null, null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<DesktopErrorResponse> handleUnavailable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new DesktopErrorResponse("DESKTOP_UNAVAILABLE", ex.getMessage(), null, null));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<DesktopErrorResponse> handleIo(IOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new DesktopErrorResponse("DESKTOP_IO_ERROR", ex.getMessage(), null, null));
    }

    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<DesktopErrorResponse> handleInterrupted(InterruptedException ex) {
        Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new DesktopErrorResponse("DESKTOP_INTERRUPTED", ex.getMessage(), null, null));
    }
}
