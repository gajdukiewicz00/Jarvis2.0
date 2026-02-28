package org.jarvis.common.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base exception handler for tool-facing controllers (todo, calendar, etc.).
 * Provides standard error responses for idempotency conflicts, validation, and payload errors.
 *
 * <p>Usage:</p>
 * <pre>
 * {@literal @}RestControllerAdvice
 * public class ToolExceptionHandler extends BaseToolExceptionHandler {
 *     // Add service-specific handlers (e.g., CalendarConflictException)
 * }
 * </pre>
 */
public abstract class BaseToolExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "idempotency_conflict",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_error",
                "details", details
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_error",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_payload",
                "message", message
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_payload",
                "message", ex.getMessage()
        ));
    }

    protected Map<String, String> fieldError(FieldError error) {
        Map<String, String> payload = new HashMap<>();
        payload.put("field", error.getField());
        payload.put("message", error.getDefaultMessage());
        return payload;
    }
}

