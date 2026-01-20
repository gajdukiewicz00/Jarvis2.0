package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.dto.CalendarConflictDTO;

import java.util.List;

public class CalendarConflictException extends RuntimeException {
    private final List<CalendarConflictDTO> conflicts;

    public CalendarConflictException(String message, List<CalendarConflictDTO> conflicts) {
        super(message);
        this.conflicts = conflicts;
    }

    public List<CalendarConflictDTO> getConflicts() {
        return conflicts;
    }
}
