package org.jarvis.apigateway.config;

import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.capability.CapabilityUnavailableException;
import org.jarvis.apigateway.proxy.UpstreamProxyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Global exception handler for api-gateway.
 * 
 * Provides structured JSON error responses for:
 * - Feign/upstream service errors
 * - Validation errors
 * - Method not allowed
 * - Resource not found
 * - Internal errors
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("http://([^/:]+)");
    private static final Pattern PATH_PATTERN = Pattern.compile("http://[^/]+(/[^\\s\\]]+)");

    // =========================================================================
    // Capability / Proxy Errors
    // =========================================================================

    @ExceptionHandler(CapabilityUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityUnavailable(
            CapabilityUnavailableException ex, WebRequest request) {

        log.info("Capability unavailable [{}:{}]: {}", ex.downstreamService(), ex.capability(), ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", ex.status().value());
        body.put("error", ex.errorCode());
        body.put("message", ex.getMessage());
        body.put("capability", ex.capability());
        body.put("upstreamService", ex.downstreamService());
        body.put("runtimeMode", ex.runtimeMode().id());
        if (!ex.supportedRuntimeModes().isEmpty()) {
            body.put("supportedRuntimeModes", ex.supportedRuntimeModes());
        }
        if (!ex.details().isEmpty()) {
            body.put("details", ex.details());
        }
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));

        return new ResponseEntity<>(body, ex.status());
    }

    @ExceptionHandler(UpstreamProxyException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamProxyException(
            UpstreamProxyException ex, WebRequest request) {

        log.warn("Proxy failure [{}{}]: {} ({})",
                ex.downstreamService(),
                ex.upstreamPath(),
                ex.errorCode(),
                ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", ex.status().value());
        body.put("error", ex.errorCode());
        body.put("message", ex.getMessage());
        body.put("upstreamService", ex.downstreamService());
        body.put("upstreamPath", ex.upstreamPath());
        if (ex.upstreamStatus() != null) {
            body.put("upstreamStatus", ex.upstreamStatus());
        }
        if (ex.upstreamBody() != null) {
            body.put("upstreamBody", ex.upstreamBody());
        }
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));

        return new ResponseEntity<>(body, ex.status());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            ResponseStatusException ex, WebRequest request) {

        String message = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return buildErrorResponse(
                HttpStatus.valueOf(ex.getStatusCode().value()),
                "REQUEST_REJECTED",
                message,
                request
        );
    }

    // =========================================================================
    // Feign/Upstream Service Errors
    // =========================================================================

    /**
     * Handle Feign timeout and connection errors.
     * Returns 503 Service Unavailable with upstream details.
     */
    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<Map<String, Object>> handleRetryableException(
            RetryableException ex, WebRequest request) {
        
        String serviceName = extractServiceName(ex.getMessage());
        String upstreamPath = extractPath(ex.getMessage());
        String method = ex.method() != null ? ex.method().name() : "UNKNOWN";
        
        // Determine error type
        String errorCode;
        String message;
        Throwable cause = ex.getCause();
        
        if (cause instanceof ConnectException) {
            errorCode = "UPSTREAM_CONNECTION_REFUSED";
            message = String.format("Connection refused to %s", serviceName);
            log.warn("Upstream connection refused [{}{}]: {}", serviceName, upstreamPath, ex.getMessage());
        } else if (cause instanceof UnknownHostException) {
            errorCode = "UPSTREAM_HOST_NOT_FOUND";
            message = String.format("Host not found: %s", serviceName);
            log.warn("Upstream host not found [{}]: {}", serviceName, ex.getMessage());
        } else if (ex.getMessage() != null && ex.getMessage().contains("Read timed out")) {
            errorCode = "UPSTREAM_TIMEOUT";
            message = String.format("Read timed out executing %s %s%s", method, serviceName, upstreamPath);
            log.warn("Upstream timeout [{}{}]: {}", serviceName, upstreamPath, ex.getMessage());
        } else {
            errorCode = "UPSTREAM_UNAVAILABLE";
            message = String.format("Service %s temporarily unavailable", serviceName);
            log.warn("Upstream unavailable [{}]: {}", serviceName, ex.getMessage());
        }
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", errorCode);
        body.put("message", message);
        body.put("upstreamService", serviceName);
        body.put("upstreamPath", upstreamPath);
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Handle Feign HTTP errors (4xx/5xx from upstream).
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> handleFeignException(
            FeignException ex, WebRequest request) {
        
        String serviceName = extractServiceName(ex.getMessage());
        String upstreamPath = extractPath(ex.getMessage());
        int upstreamStatus = ex.status();
        
        // Handle connection errors (status = -1)
        if (upstreamStatus == -1) {
            return handleUpstreamUnavailable(ex, serviceName, upstreamPath, request);
        }
        
        HttpStatus status;
        String errorCode;
        String logMessage;
        
        if (upstreamStatus >= 500) {
            // Upstream server error → 502 Bad Gateway
            status = HttpStatus.BAD_GATEWAY;
            errorCode = "UPSTREAM_ERROR";
            logMessage = String.format("Upstream server error [%s]: %d", serviceName, upstreamStatus);
            log.warn(logMessage);
        } else if (upstreamStatus >= 400) {
            // Upstream client error → pass through status
            status = HttpStatus.valueOf(upstreamStatus);
            errorCode = "UPSTREAM_CLIENT_ERROR";
            logMessage = String.format("Upstream client error [%s]: %d", serviceName, upstreamStatus);
            log.debug(logMessage);
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "UPSTREAM_UNKNOWN";
            log.error("Unexpected upstream status [{}]: {}", serviceName, upstreamStatus);
        }
        
        // Try to parse upstream response body
        String upstreamMessage = parseUpstreamMessage(ex);
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", "Error from upstream service");
        body.put("upstreamService", serviceName);
        body.put("upstreamStatus", upstreamStatus);
        body.put("upstreamPath", upstreamPath);
        if (upstreamMessage != null && !upstreamMessage.isEmpty()) {
            body.put("upstreamMessage", truncate(upstreamMessage, 500));
        }
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    private ResponseEntity<Map<String, Object>> handleUpstreamUnavailable(
            FeignException ex, String serviceName, String upstreamPath, WebRequest request) {
        
        log.warn("Upstream unavailable [{}{}]: {}", serviceName, upstreamPath, ex.getMessage());
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "UPSTREAM_UNAVAILABLE");
        body.put("message", "Service temporarily unavailable");
        body.put("upstreamService", serviceName);
        body.put("upstreamPath", upstreamPath);
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, HttpStatus.SERVICE_UNAVAILABLE);
    }

    // =========================================================================
    // Validation Errors
    // =========================================================================

    /**
     * Handle validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        
        log.debug("Validation error: {}", message);
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            message,
            request
        );
    }

    /**
     * Handle missing request parameters.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            MissingServletRequestParameterException ex, WebRequest request) {
        
        String message = String.format("Required parameter '%s' is missing", ex.getParameterName());
        log.debug("Missing parameter: {}", ex.getParameterName());
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "MISSING_PARAMETER",
            message,
            request
        );
    }

    // =========================================================================
    // HTTP Errors
    // =========================================================================

    /**
     * Handle method not supported (405).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        
        Set<String> supportedMethods = ex.getSupportedHttpMethods() != null 
                ? ex.getSupportedHttpMethods().stream()
                      .map(method -> method.name())
                      .collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        
        log.debug("Method not supported: {} for path {}", ex.getMethod(), extractRequestPath(request));
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.METHOD_NOT_ALLOWED.value());
        body.put("error", "METHOD_NOT_ALLOWED");
        body.put("message", String.format("Request method '%s' is not supported", ex.getMethod()));
        body.put("supportedMethods", supportedMethods);
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handle resource not found (404).
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoHandlerFoundException ex, WebRequest request) {
        
        log.debug("Resource not found: {}", ex.getRequestURL());
        
        return buildErrorResponse(
            HttpStatus.NOT_FOUND,
            "NOT_FOUND",
            String.format("No handler found for %s %s", ex.getHttpMethod(), ex.getRequestURL()),
            request
        );
    }

    /**
     * Handle file upload size exceeded.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, WebRequest request) {
        
        log.warn("File upload size exceeded: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "PAYLOAD_TOO_LARGE",
            "File size exceeds maximum allowed size",
            request
        );
    }

    // =========================================================================
    // Generic Errors
    // =========================================================================

    /**
     * Handle illegal argument exceptions.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, WebRequest request) {
        
        log.warn("Illegal argument: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "INVALID_ARGUMENT",
            ex.getMessage(),
            request
        );
    }

    /**
     * Handle authorization denials (403). Method-security (@PreAuthorize) throws
     * AuthorizationDeniedException (a subclass of AccessDeniedException) AFTER the
     * Spring Security filter chain, so it escapes to MVC and — without this handler —
     * would fall through to the catch-all below and be reported as a 500. Map it to
     * a proper 403 so a non-authorized caller is Forbidden, not Server Error.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex, WebRequest request) {

        log.warn("Access denied [{}]: {}", extractRequestPath(request), ex.getMessage());

        return buildErrorResponse(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "You do not have permission to perform this action",
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

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String errorCode, String message, WebRequest request) {
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", message);
        body.put("service", "api-gateway");
        body.put("path", extractRequestPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    private String extractServiceName(String message) {
        if (message == null) return "unknown";
        Matcher matcher = SERVICE_NAME_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    private String extractPath(String message) {
        if (message == null) return "";
        Matcher matcher = PATH_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String extractRequestPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.replace("uri=", "");
    }

    private String parseUpstreamMessage(FeignException ex) {
        try {
            String content = ex.contentUTF8();
            if (content != null && !content.isEmpty()) {
                return content;
            }
        } catch (RuntimeException e) {
            // Ignore parsing errors
        }
        return null;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
