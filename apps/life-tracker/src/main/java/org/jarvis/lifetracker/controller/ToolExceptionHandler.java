package org.jarvis.lifetracker.controller;

import org.jarvis.common.exception.BaseToolExceptionHandler;
import org.jarvis.lifetracker.service.CalendarConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ToolExceptionHandler extends BaseToolExceptionHandler {

    @ExceptionHandler(CalendarConflictException.class)
    public ResponseEntity<Map<String, Object>> handleCalendarConflict(CalendarConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "calendar_conflict",
                "message", ex.getMessage(),
                "conflicts", ex.getConflicts()
        ));
    }
}
