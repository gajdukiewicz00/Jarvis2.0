package org.jarvis.voicegateway.config;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.jarvis.voicegateway.exception.TtsUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for voice-gateway service.
 * Provides structured JSON error responses for all exceptions.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle STT unavailable exception.
     * Returns 503 Service Unavailable with clear message.
     */
    @ExceptionHandler(SttUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleSttUnavailable(
            SttUnavailableException ex, WebRequest request) {
        
        log.warn("STT unavailable: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE,
            "STT_UNAVAILABLE",
            ex.getMessage(),
            request
        );
    }

    @ExceptionHandler(TtsUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleTtsUnavailable(
            TtsUnavailableException ex, WebRequest request) {

        log.warn("TTS unavailable: {}", ex.getMessage());

        return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TTS_UNAVAILABLE",
                ex.getMessage(),
                request
        );
    }

    /**
     * Handle illegal argument exceptions.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, WebRequest request) {
        
        log.warn("Invalid request: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            ex.getMessage(),
            request
        );
    }

    /**
     * Catch-all handler for unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, WebRequest request) {
        
        log.error("Unexpected error in voice-gateway: {}", ex.getMessage(), ex);
        
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred during voice processing",
            request
        );
    }

    /**
     * Build a structured error response.
     */
    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String errorCode, String message, WebRequest request) {
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", message);
        body.put("service", "voice-gateway");
        body.put("path", extractPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.replace("uri=", "");
    }
}
