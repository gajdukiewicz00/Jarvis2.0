package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.service.CalendarService;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
            @RequestBody EventRequest request,
            HttpServletRequest httpRequest) {
        log.info("Adding event: {}", request.title());
        CalendarEvent event = new CalendarEvent();
        event.setUserId(requireUserId(httpRequest));
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
            @PathVariable Long id,
            @RequestBody MoveEventRequest request,
            HttpServletRequest httpRequest) {
        log.info("Moving event {}", id);
        return calendarService.moveEvent(requireUserId(httpRequest), id, request.newStartTime(), request.newEndTime());
    }

    @GetMapping("/events")
    public List<CalendarEventDTO> getEvents(
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            HttpServletRequest httpRequest) {
        return calendarService.listEvents(requireUserId(httpRequest), from, to);
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

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
