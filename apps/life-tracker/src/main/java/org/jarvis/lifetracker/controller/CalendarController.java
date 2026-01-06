package org.jarvis.lifetracker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.repository.CalendarEventRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/life/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarEventRepository calendarEventRepository;
    private final org.jarvis.lifetracker.service.DTOMapper dtoMapper;

    @PostMapping("/event")
    public org.jarvis.lifetracker.dto.CalendarEventDTO addEvent(@RequestBody EventRequest request) {
        log.info("Adding event: {}", request.title());
        CalendarEvent event = new CalendarEvent();
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setStartTime(request.startTime());
        event.setEndTime(request.endTime());
        event.setAllDay(request.allDay());
        CalendarEvent saved = calendarEventRepository.save(event);
        return dtoMapper.toDTO(saved);
    }

    @GetMapping("/events")
    public List<org.jarvis.lifetracker.dto.CalendarEventDTO> getEvents() {
        return calendarEventRepository.findAll().stream()
                .map(dtoMapper::toDTO)
                .toList();
    }

    public record EventRequest(String title, String description, LocalDateTime startTime, LocalDateTime endTime,
            boolean allDay) {
    }
}
