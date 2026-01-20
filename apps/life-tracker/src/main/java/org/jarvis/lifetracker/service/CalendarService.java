package org.jarvis.lifetracker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.dto.CalendarConflictDTO;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.dto.FreeSlotDTO;
import org.jarvis.lifetracker.repository.CalendarEventRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final int MAX_RECURRENCE_OCCURRENCES = 60;

    private final CalendarEventRepository calendarEventRepository;
    private final DTOMapper dtoMapper;

    public CalendarEventDTO createEvent(CalendarEvent event) {
        validateEvent(event);
        List<CalendarConflictDTO> conflicts = findConflicts(event, null);
        if (!conflicts.isEmpty()) {
            throw new CalendarConflictException("Calendar conflict detected", conflicts);
        }
        CalendarEvent saved = calendarEventRepository.save(event);
        return dtoMapper.toDTO(saved);
    }

    public CalendarEventDTO moveEvent(Long eventId, LocalDateTime newStart, LocalDateTime newEnd) {
        CalendarEvent event = calendarEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));

        event.setStartTime(newStart);
        event.setEndTime(newEnd);
        validateEvent(event);

        List<CalendarConflictDTO> conflicts = findConflicts(event, eventId);
        if (!conflicts.isEmpty()) {
            throw new CalendarConflictException("Calendar conflict detected", conflicts);
        }

        CalendarEvent saved = calendarEventRepository.save(event);
        return dtoMapper.toDTO(saved);
    }

    public List<CalendarEventDTO> listEvents(String userId, LocalDateTime from, LocalDateTime to) {
        List<CalendarEvent> events = calendarEventRepository.findByUserId(userId);
        return events.stream()
                .filter(event -> withinRange(event.getStartTime(), event.getEndTime(), from, to))
                .map(dtoMapper::toDTO)
                .toList();
    }

    public FreeSlotDTO findFreeSlot(String userId, LocalDateTime from, LocalDateTime to, int durationMinutes,
            WorkHours workHours) {
        List<CalendarEvent> events = calendarEventRepository.findByUserId(userId);
        List<TimeSlot> busySlots = new ArrayList<>();
        for (CalendarEvent event : events) {
            busySlots.addAll(expandEvent(event, from, to));
        }
        busySlots.sort(Comparator.comparing(slot -> slot.start));

        LocalDateTime cursor = from;
        while (cursor.isBefore(to)) {
            cursor = alignToWorkHours(cursor, workHours);
            LocalDateTime candidateEnd = cursor.plusMinutes(durationMinutes);
            if (candidateEnd.isAfter(to)) {
                break;
            }

            boolean overlaps = false;
            for (TimeSlot slot : busySlots) {
                if (!candidateEnd.isAfter(slot.start)) {
                    break;
                }
                if (cursor.isBefore(slot.end) && candidateEnd.isAfter(slot.start)) {
                    cursor = slot.end;
                    overlaps = true;
                    break;
                }
            }

            if (!overlaps) {
                return new FreeSlotDTO(cursor, candidateEnd);
            }
        }

        return null;
    }

    private List<CalendarConflictDTO> findConflicts(CalendarEvent candidate, Long excludeId) {
        LocalDateTime windowStart = candidate.getStartTime();
        LocalDateTime windowEnd = Optional.ofNullable(candidate.getEndTime())
                .orElse(candidate.getStartTime().plusMinutes(30));
        if (candidate.getRecurrenceRule() != null && !candidate.getRecurrenceRule().isBlank()) {
            windowEnd = Optional.ofNullable(candidate.getRecurrenceUntil())
                    .orElse(windowEnd.plusDays(30));
        }

        List<CalendarEvent> existing = calendarEventRepository.findByUserId(candidate.getUserId());
        List<TimeSlot> candidateSlots = expandEvent(candidate, windowStart, windowEnd);
        List<CalendarConflictDTO> conflicts = new ArrayList<>();

        for (CalendarEvent event : existing) {
            if (excludeId != null && excludeId.equals(event.getId())) {
                continue;
            }
            List<TimeSlot> eventSlots = expandEvent(event, windowStart, windowEnd);
            for (TimeSlot candidateSlot : candidateSlots) {
                for (TimeSlot eventSlot : eventSlots) {
                    if (overlaps(candidateSlot, eventSlot)) {
                        conflicts.add(new CalendarConflictDTO(
                                event.getId(),
                                event.getTitle(),
                                eventSlot.start,
                                eventSlot.end,
                                event.isAllDay(),
                                event.getLocation(),
                                event.getRecurrenceRule()
                        ));
                        break;
                    }
                }
            }
        }
        return conflicts;
    }

    private List<TimeSlot> expandEvent(CalendarEvent event, LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<TimeSlot> slots = new ArrayList<>();
        LocalDateTime start = event.getStartTime();
        LocalDateTime end = event.getEndTime() != null ? event.getEndTime() : start.plusMinutes(30);
        if (start.isAfter(windowEnd) || end.isBefore(windowStart)) {
            return slots;
        }

        if (event.getRecurrenceRule() == null || event.getRecurrenceRule().isBlank()) {
            slots.add(new TimeSlot(start, end));
            return slots;
        }

        RecurrenceRule rule = RecurrenceRule.parse(event.getRecurrenceRule());
        LocalDateTime recurrenceUntil = Optional.ofNullable(event.getRecurrenceUntil())
                .orElse(rule.until != null ? rule.until : windowEnd);

        int occurrences = 0;
        LocalDateTime currentStart = start;
        LocalDateTime currentEnd = end;

        while (!currentStart.isAfter(windowEnd)
                && !currentStart.isAfter(recurrenceUntil)
                && occurrences < rule.countLimit(MAX_RECURRENCE_OCCURRENCES)) {
            if (!currentEnd.isBefore(windowStart)) {
                slots.add(new TimeSlot(currentStart, currentEnd));
            }
            occurrences++;
            currentStart = rule.next(currentStart);
            currentEnd = currentStart.plus(Duration.between(start, end));
        }

        return slots;
    }

    private boolean overlaps(TimeSlot a, TimeSlot b) {
        return a.start.isBefore(b.end) && b.start.isBefore(a.end);
    }

    private boolean withinRange(LocalDateTime start, LocalDateTime end, LocalDateTime from, LocalDateTime to) {
        LocalDateTime actualEnd = end != null ? end : start.plusMinutes(30);
        if (from != null && actualEnd.isBefore(from)) {
            return false;
        }
        if (to != null && start.isAfter(to)) {
            return false;
        }
        return true;
    }

    private LocalDateTime alignToWorkHours(LocalDateTime cursor, WorkHours workHours) {
        if (workHours == null) {
            return cursor;
        }
        LocalDate date = cursor.toLocalDate();
        LocalTime start = workHours.start;
        LocalTime end = workHours.end;
        LocalDateTime dayStart = LocalDateTime.of(date, start);
        LocalDateTime dayEnd = LocalDateTime.of(date, end);

        if (cursor.isBefore(dayStart)) {
            return dayStart;
        }
        if (cursor.isAfter(dayEnd)) {
            return LocalDateTime.of(date.plusDays(1), start);
        }
        return cursor;
    }

    private void validateEvent(CalendarEvent event) {
        if (event.getUserId() == null || event.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (event.getTitle() == null || event.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (event.getStartTime() == null || event.getEndTime() == null) {
            throw new IllegalArgumentException("startTime and endTime are required");
        }
        if (event.getEndTime().isBefore(event.getStartTime())) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
        if (event.getSource() == null) {
            event.setSource(EntrySource.MANUAL);
        }
    }

    private record TimeSlot(LocalDateTime start, LocalDateTime end) {
    }

    public record WorkHours(LocalTime start, LocalTime end) {
        public static WorkHours fromStrings(String start, String end) {
            if (start == null || end == null) {
                return null;
            }
            return new WorkHours(LocalTime.parse(start), LocalTime.parse(end));
        }
    }

    private static class RecurrenceRule {
        private final Frequency frequency;
        private final int interval;
        private final Integer count;
        private final LocalDateTime until;

        private RecurrenceRule(Frequency frequency, int interval, Integer count, LocalDateTime until) {
            this.frequency = frequency;
            this.interval = interval;
            this.count = count;
            this.until = until;
        }

        static RecurrenceRule parse(String rule) {
            Map<String, String> parts = java.util.Arrays.stream(rule.split(";"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.split("=", 2))
                    .filter(pair -> pair.length == 2)
                    .collect(java.util.stream.Collectors.toMap(
                            pair -> pair[0].toUpperCase(Locale.ROOT),
                            pair -> pair[1]));

            Frequency frequency = Frequency.valueOf(parts.getOrDefault("FREQ", "DAILY"));
            int interval = Integer.parseInt(parts.getOrDefault("INTERVAL", "1"));
            Integer count = parts.containsKey("COUNT") ? Integer.parseInt(parts.get("COUNT")) : null;
            LocalDateTime until = parts.containsKey("UNTIL")
                    ? LocalDateTime.parse(parts.get("UNTIL"))
                    : null;

            return new RecurrenceRule(frequency, interval, count, until);
        }

        LocalDateTime next(LocalDateTime current) {
            return switch (frequency) {
                case DAILY -> current.plusDays(interval);
                case WEEKLY -> current.plusWeeks(interval);
                case MONTHLY -> current.plusMonths(interval);
            };
        }

        int countLimit(int max) {
            if (count == null) {
                return max;
            }
            return Math.min(count, max);
        }

        enum Frequency {
            DAILY,
            WEEKLY,
            MONTHLY
        }
    }
}
