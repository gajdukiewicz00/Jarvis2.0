package org.jarvis.lifetracker.controller;

import jakarta.validation.ConstraintViolationException;
import org.jarvis.lifetracker.service.CalendarConflictException;
import org.jarvis.lifetracker.tooling.IdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ToolExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "idempotency_conflict",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(CalendarConflictException.class)
    public ResponseEntity<Map<String, Object>> handleCalendarConflict(CalendarConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "calendar_conflict",
                "message", ex.getMessage(),
                "conflicts", ex.getConflicts()
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

    private Map<String, String> fieldError(FieldError error) {
        Map<String, String> payload = new HashMap<>();
        payload.put("field", error.getField());
        payload.put("message", error.getDefaultMessage());
        return payload;
    }
}
