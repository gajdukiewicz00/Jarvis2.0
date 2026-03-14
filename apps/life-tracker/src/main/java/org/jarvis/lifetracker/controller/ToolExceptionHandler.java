package org.jarvis.lifetracker.controller;

import org.jarvis.common.exception.BaseToolExceptionHandler;
import org.jarvis.lifetracker.service.CalendarEventNotFoundException;
import org.jarvis.lifetracker.service.CalendarConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ToolExceptionHandler extends BaseToolExceptionHandler {

    @ExceptionHandler(CalendarConflictException.class)
    public ResponseEntity<Map<String, Object>> handleCalendarConflict(CalendarConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "calendar_conflict",
                "message", ex.getMessage(),
                "conflicts", ex.getConflicts()
        ));
    }

    @ExceptionHandler(CalendarEventNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCalendarEventNotFound(CalendarEventNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "calendar_event_not_found",
                "message", ex.getMessage(),
                "userId", ex.getUserId(),
                "eventId", ex.getEventId()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString().toLowerCase(),
                "message", ex.getReason() != null ? ex.getReason() : "Request failed"
        ));
    }
}
