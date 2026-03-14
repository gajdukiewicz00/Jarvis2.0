package org.jarvis.lifetracker.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for life-tracker service.
 * Provides structured JSON error responses for all exceptions.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    /**
     * Handle database connection/transaction errors.
     * Returns 503 Service Unavailable with structured error.
     */
    @ExceptionHandler({
        CannotCreateTransactionException.class,
        org.hibernate.exception.JDBCConnectionException.class
    })
    public ResponseEntity<Map<String, Object>> handleDatabaseConnectionException(
            Exception ex, WebRequest request) {
        
        log.error("Database connection error: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE,
            "DATABASE_UNAVAILABLE",
            "Database temporarily unavailable. Please try again later.",
            request
        );
    }

    /**
     * Handle SQL exceptions (query errors, constraint violations, etc.)
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSQLException(
            SQLException ex, WebRequest request) {
        
        log.error("SQL error [{}]: {}", ex.getSQLState(), ex.getMessage());
        
        // Check for specific SQL states
        String sqlState = ex.getSQLState();
        if (sqlState != null) {
            // 23xxx = integrity constraint violation
            if (sqlState.startsWith("23")) {
                return buildErrorResponse(
                    HttpStatus.CONFLICT,
                    "CONSTRAINT_VIOLATION",
                    "Data constraint violation: " + extractUserMessage(ex),
                    request
                );
            }
            // 08xxx = connection exception
            if (sqlState.startsWith("08")) {
                return buildErrorResponse(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATABASE_UNAVAILABLE",
                    "Database connection lost. Please try again.",
                    request
                );
            }
        }
        
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DATABASE_ERROR",
            "A database error occurred.",
            request
        );
    }

    /**
     * Handle Spring Data access exceptions.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessException(
            DataAccessException ex, WebRequest request) {
        
        log.error("Data access error: {}", ex.getMessage());
        
        // Check if it's a connection issue
        Throwable rootCause = ex.getRootCause();
        if (rootCause instanceof SQLException sqlEx) {
            return handleSQLException(sqlEx, request);
        }
        
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DATA_ACCESS_ERROR",
            "An error occurred while accessing data.",
            request
        );
    }

    /**
     * Handle validation errors (e.g., missing required fields).
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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            ResponseStatusException ex, WebRequest request) {

        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return buildErrorResponse(
                status,
                ex.getReason() != null ? ex.getReason().toUpperCase().replace(' ', '_') : status.name(),
                ex.getReason() != null ? ex.getReason() : "Request failed",
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
            "An unexpected error occurred. Please try again later.",
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
        body.put("service", "life-tracker");
        body.put("path", extractPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    /**
     * Extract path from WebRequest.
     */
    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.replace("uri=", "");
    }

    /**
     * Extract user-friendly message from SQLException.
     */
    private String extractUserMessage(SQLException ex) {
        String message = ex.getMessage();
        // Remove technical details, keep the essence
        if (message != null && message.contains("Detail:")) {
            int detailIndex = message.indexOf("Detail:");
            return message.substring(detailIndex + 8).trim();
        }
        return "Please check your input data.";
    }
}
