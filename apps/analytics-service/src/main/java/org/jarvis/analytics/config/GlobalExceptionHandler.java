package org.jarvis.analytics.config;

import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global exception handler for analytics-service.
 * Handles Feign exceptions from life-tracker calls gracefully.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("http://([^/:]+)");
    private static final Pattern PATH_PATTERN = Pattern.compile("http://[^/]+(/[^\\s]+)");

    /**
     * Handle Feign timeout/connection errors (RetryableException).
     * Returns 503 with upstream service info.
     */
    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<Map<String, Object>> handleRetryableException(
            RetryableException ex, WebRequest request) {
        
        String serviceName = extractServiceName(ex.getMessage());
        String path = extractPath(ex.getMessage());
        
        log.warn("Upstream service timeout [{}{}]: {}", 
                serviceName, path, ex.getMessage());
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "UPSTREAM_TIMEOUT");
        body.put("message", "Upstream service temporarily unavailable");
        body.put("upstreamService", serviceName);
        body.put("upstreamPath", path);
        body.put("service", "analytics-service");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Handle Feign HTTP errors (4xx, 5xx from upstream).
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> handleFeignException(
            FeignException ex, WebRequest request) {
        
        String serviceName = extractServiceName(ex.getMessage());
        String path = extractPath(ex.getMessage());
        int upstreamStatus = ex.status();
        
        // Determine response status based on upstream error
        HttpStatus status;
        String errorCode;
        
        if (upstreamStatus == -1) {
            // Connection error (DNS, connection refused, etc.)
            status = HttpStatus.SERVICE_UNAVAILABLE;
            errorCode = "UPSTREAM_UNAVAILABLE";
            log.warn("Upstream service unavailable [{}]: {}", serviceName, ex.getMessage());
        } else if (upstreamStatus >= 500) {
            // Upstream server error
            status = HttpStatus.BAD_GATEWAY;
            errorCode = "UPSTREAM_ERROR";
            log.warn("Upstream server error [{}]: {} - {}", 
                    serviceName, upstreamStatus, ex.getMessage());
        } else if (upstreamStatus >= 400) {
            // Upstream client error - pass through
            status = HttpStatus.valueOf(upstreamStatus);
            errorCode = "UPSTREAM_CLIENT_ERROR";
            log.debug("Upstream client error [{}]: {} - {}", 
                    serviceName, upstreamStatus, ex.getMessage());
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "UPSTREAM_UNKNOWN_ERROR";
            log.error("Unexpected upstream response [{}]: {} - {}", 
                    serviceName, upstreamStatus, ex.getMessage());
        }
        
        // Try to extract error message from upstream response
        String upstreamMessage = ex.contentUTF8();
        if (upstreamMessage == null || upstreamMessage.isEmpty()) {
            upstreamMessage = "No details available";
        }
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", "Error from upstream service");
        body.put("upstreamService", serviceName);
        body.put("upstreamStatus", upstreamStatus);
        body.put("upstreamPath", path);
        body.put("upstreamMessage", truncateMessage(upstreamMessage, 500));
        body.put("service", "analytics-service");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    /**
     * Handle validation errors.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        
        log.warn("Validation error: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
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
        
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
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
        body.put("service", "analytics-service");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    /**
     * Extract service name from Feign error message.
     */
    private String extractServiceName(String message) {
        if (message == null) return "unknown";
        Matcher matcher = SERVICE_NAME_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    /**
     * Extract path from Feign error message.
     */
    private String extractPath(String message) {
        if (message == null) return "";
        Matcher matcher = PATH_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Extract path from WebRequest.
     */
    private String extractRequestPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.replace("uri=", "");
    }

    /**
     * Truncate message to max length.
     */
    private String truncateMessage(String message, int maxLength) {
        if (message == null) return "";
        if (message.length() <= maxLength) return message;
        return message.substring(0, maxLength) + "...";
    }
}

