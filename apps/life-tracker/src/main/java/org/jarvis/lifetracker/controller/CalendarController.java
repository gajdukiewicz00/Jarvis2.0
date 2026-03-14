package org.jarvis.lifetracker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.service.CalendarService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/life/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    @PostMapping("/event")
    public CalendarEventDTO addEvent(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody EventRequest request) {
        log.info("Adding event: {}", request.title());
        CalendarEvent event = new CalendarEvent();
        String userId = headerUserId != null ? headerUserId : request.userId();
        event.setUserId(userId);
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        event.setAllDay(request.allDay());
        event.setLocation(request.location());
        event.setRecurrenceRule(request.recurrenceRule());
        event.setRecurrenceUntil(request.recurrenceUntil());
        event.setTimezone(request.timezone());
        event.setSource(EntrySource.MANUAL);
        return calendarService.createEvent(event);
    }

    @PutMapping("/event/{id}")
    public CalendarEventDTO moveEvent(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable Long id,
            @RequestBody MoveEventRequest request) {
        log.info("Moving event {}", id);
        return calendarService.moveEvent(userId, id, request.newStartTime(), request.newEndTime());
    }

    @GetMapping("/events")
    public List<CalendarEventDTO> getEvents(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return calendarService.listEvents(resolvedUserId, from, to);
    }

    public record EventRequest(
            String userId,
            String title,
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean allDay,
            String location,
            String recurrenceRule,
            LocalDateTime recurrenceUntil,
            String timezone) {
    }

    public record MoveEventRequest(LocalDateTime newStartTime, LocalDateTime newEndTime) {
    }
}
