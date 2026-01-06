package org.jarvis.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base exception handler providing common error handling for all services.
 * 
 * Usage:
 * <pre>
 * @RestControllerAdvice
 * public class GlobalExceptionHandler extends BaseGlobalExceptionHandler {
 *     // Add service-specific handlers if needed
 * }
 * </pre>
 */
@Slf4j
public abstract class BaseGlobalExceptionHandler {

    /**
     * Returns the service name for error responses.
     */
    protected abstract String getServiceName();

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, WebRequest request) {
        log.error("Unhandled exception in {}: {}", getServiceName(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Invalid argument in {}: {}", getServiceName(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid argument", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("Validation error in {}: {}", getServiceName(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation error", message, request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException ex, WebRequest request) {
        log.debug("Not found in {}: {} {}", getServiceName(), ex.getHttpMethod(), ex.getRequestURL());
        return buildResponse(HttpStatus.NOT_FOUND, "Not found", 
                "Endpoint not found: " + ex.getHttpMethod() + " " + ex.getRequestURL(), request);
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> handleHttpClientError(HttpClientErrorException ex, WebRequest request) {
        log.warn("HTTP client error in {}: {} - {}", getServiceName(), 
                ex.getStatusCode(), StringUtils.truncate(ex.getResponseBodyAsString(), 200));
        return buildResponse(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                "Client error",
                ex.getStatusText(),
                request
        );
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<Map<String, Object>> handleHttpServerError(HttpServerErrorException ex, WebRequest request) {
        log.error("HTTP server error in {}: {} - {}", getServiceName(), 
                ex.getStatusCode(), StringUtils.truncate(ex.getResponseBodyAsString(), 200));
        return buildResponse(HttpStatus.BAD_GATEWAY, "Upstream service error", ex.getStatusText(), request);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleResourceAccess(ResourceAccessException ex, WebRequest request) {
        log.error("Resource access error in {}: {}", getServiceName(), ex.getMessage());
        String message = ex.getMessage();
        if (message != null && message.contains("timed out")) {
            return buildResponse(HttpStatus.GATEWAY_TIMEOUT, "Request timeout", 
                    "Upstream service did not respond in time", request);
        }
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", 
                "Failed to connect to upstream service", request);
    }

    /**
     * Builds a standardized error response.
     */
    protected ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, 
            String error, 
            String message, 
            WebRequest request) {
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("service", getServiceName());
        body.put("path", extractPath(request));

        return ResponseEntity.status(status).body(body);
    }

    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        if (description.startsWith("uri=")) {
            return description.substring(4);
        }
        return description;
    }
}

