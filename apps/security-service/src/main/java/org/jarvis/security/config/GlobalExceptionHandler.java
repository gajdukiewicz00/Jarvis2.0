package org.jarvis.security.config;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.util.TokenMaskingUtil;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for security-service.
 * Separates business errors from technical errors and provides structured JSON responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // =========================================================================
    // Business Errors (4xx)
    // =========================================================================

    /**
     * Handle authentication failures (invalid credentials, user not found).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(
            AuthenticationException ex, WebRequest request) {
        
        log.info("Authentication failed: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.UNAUTHORIZED,
            ex.getErrorCode(),
            ex.getMessage(),
            request
        );
    }

    /**
     * Handle authorization failures (valid caller, wrong role).
     */
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthorizationException(
            AuthorizationException ex, WebRequest request) {

        log.warn("Authorization failed: {}", ex.getMessage());

        return buildErrorResponse(
            HttpStatus.FORBIDDEN,
            ex.getErrorCode(),
            ex.getMessage(),
            request
        );
    }

    /**
     * Handle user already exists during registration.
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUserAlreadyExistsException(
            UserAlreadyExistsException ex, WebRequest request) {
        
        log.info("Registration failed - user exists: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.CONFLICT,
            "USER_ALREADY_EXISTS",
            ex.getMessage(),
            request
        );
    }

    /**
     * Handle validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        
        log.warn("Validation error: {}", message);
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            message,
            request
        );
    }

    /**
     * Handle illegal argument exceptions (business validation).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        
        log.warn("Business validation error: {}", ex.getMessage());
        
        return buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            ex.getMessage(),
            request
        );
    }

    // =========================================================================
    // Technical Errors (5xx)
    // =========================================================================

    /**
     * Handle database connection/transaction errors.
     * Returns 503 Service Unavailable.
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
            "AUTH_SERVICE_UNAVAILABLE",
            "Authentication temporarily unavailable due to database issues",
            request
        );
    }

    /**
     * Handle data integrity violations (unique constraint, foreign key, etc.)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {
        
        String message = ex.getMessage();
        
        // Check for unique constraint violation (username already exists)
        if (message != null && message.contains("unique")) {
            log.info("Unique constraint violation: {}", message);
            return buildErrorResponse(
                HttpStatus.CONFLICT,
                "USER_ALREADY_EXISTS",
                "Username already exists",
                request
            );
        }
        
        log.error("Data integrity violation: {}", message);
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DATA_ERROR",
            "A data error occurred",
            request
        );
    }

    /**
     * Handle SQL exceptions.
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSQLException(
            SQLException ex, WebRequest request) {
        
        log.error("SQL error [{}]: {}", ex.getSQLState(), ex.getMessage());
        
        // Connection errors
        if (ex.getSQLState() != null && ex.getSQLState().startsWith("08")) {
            return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_SERVICE_UNAVAILABLE",
                "Authentication temporarily unavailable due to database issues",
                request
            );
        }
        
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DATABASE_ERROR",
            "A database error occurred",
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
        
        // Check for connection issues
        Throwable rootCause = ex.getRootCause();
        if (rootCause instanceof SQLException sqlEx) {
            return handleSQLException(sqlEx, request);
        }
        
        return buildErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AUTH_SERVICE_UNAVAILABLE",
            "Authentication temporarily unavailable",
            request
        );
    }

    /**
     * Catch-all handler for unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, WebRequest request) {

        // Message is passed through TokenMaskingUtil as defense-in-depth: no
        // exception message in this service is expected to embed a raw token, but
        // this is the catch-all path for every unanticipated exception, so it must
        // never be the place a token slips into the logs.
        log.error("Unexpected error in security-service: {}", TokenMaskingUtil.maskTokensInText(ex.getMessage()), ex);

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
        body.put("service", "security-service");
        body.put("path", extractPath(request));
        
        return new ResponseEntity<>(body, status);
    }

    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.replace("uri=", "");
    }

    // =========================================================================
    // Custom Exceptions
    // =========================================================================

    /**
     * Custom authentication exception with error code.
     */
    public static class AuthenticationException extends RuntimeException {
        private final String errorCode;

        public AuthenticationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * User already exists exception.
     */
    public static class UserAlreadyExistsException extends RuntimeException {
        public UserAlreadyExistsException(String message) {
            super(message);
        }
    }

    /**
     * Authorization failure: caller is authenticated (their token is valid)
     * but does not hold the role required for the action. Kept distinct from
     * {@link AuthenticationException} (401) since this is a 403 case.
     */
    public static class AuthorizationException extends RuntimeException {
        private final String errorCode;

        public AuthorizationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}

