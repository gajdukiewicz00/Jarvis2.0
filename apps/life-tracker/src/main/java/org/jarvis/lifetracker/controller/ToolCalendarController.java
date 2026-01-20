package org.jarvis.lifetracker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.dto.FreeSlotDTO;
import org.jarvis.lifetracker.service.CalendarService;
import org.jarvis.lifetracker.tooling.ToolRequestService;
import org.jarvis.lifetracker.tooling.dto.CreateEventToolRequest;
import org.jarvis.lifetracker.tooling.dto.FindFreeSlotToolRequest;
import org.jarvis.lifetracker.tooling.dto.ListEventsToolRequest;
import org.jarvis.lifetracker.tooling.dto.MoveEventToolRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/tools/calendar")
@RequiredArgsConstructor
@Validated
public class ToolCalendarController {

    private final CalendarService calendarService;
    private final ToolRequestService toolRequestService;

    @PostMapping("/create")
    public ResponseEntity<CalendarEventDTO> createEvent(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody CreateEventToolRequest request) {

        String requestHash = toolRequestService.hashRequest(request);
        Optional<CalendarEventDTO> cached = toolRequestService.loadCachedResponse(
                idempotencyKey, "create_event", userId, requestHash, CalendarEventDTO.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        CalendarEvent event = new CalendarEvent();
        event.setUserId(userId);
        event.setTitle(request.getTitle());
        event.setDescription(request.getDescription());
        event.setStartTime(request.getStartTime());
        event.setEndTime(request.getEndTime());
        event.setAllDay(Boolean.TRUE.equals(request.getAllDay()));
        event.setLocation(request.getLocation());
        event.setRecurrenceRule(request.getRecurrenceRule());
        event.setRecurrenceUntil(request.getRecurrenceUntil());
        event.setTimezone(request.getTimezone());
        event.setSource(EntrySource.AI);

        CalendarEventDTO created = calendarService.createEvent(event);
        toolRequestService.storeResponse(idempotencyKey, "create_event", userId, requestHash, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/move")
    public ResponseEntity<CalendarEventDTO> moveEvent(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody MoveEventToolRequest request) {

        String requestHash = toolRequestService.hashRequest(request);
        Optional<CalendarEventDTO> cached = toolRequestService.loadCachedResponse(
                idempotencyKey, "move_event", userId, requestHash, CalendarEventDTO.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        CalendarEventDTO moved = calendarService.moveEvent(
                request.getEventId(),
                request.getNewStartTime(),
                request.getNewEndTime());
        toolRequestService.storeResponse(idempotencyKey, "move_event", userId, requestHash, moved);
        return ResponseEntity.ok(moved);
    }

    @PostMapping("/list")
    public ResponseEntity<List<CalendarEventDTO>> listEvents(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody ListEventsToolRequest request) {
        List<CalendarEventDTO> events = calendarService.listEvents(userId, request.getFrom(), request.getTo());
        return ResponseEntity.ok(events);
    }

    @PostMapping("/free-slot")
    public ResponseEntity<FreeSlotDTO> findFreeSlot(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody FindFreeSlotToolRequest request) {
        CalendarService.WorkHours workHours = CalendarService.WorkHours.fromStrings(
                request.getWorkHoursStart(), request.getWorkHoursEnd());
        FreeSlotDTO slot = calendarService.findFreeSlot(
                userId,
                request.getFrom(),
                request.getTo(),
                request.getDurationMinutes(),
                workHours);
        return ResponseEntity.ok(slot);
    }
}
