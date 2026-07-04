package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.dto.CalendarConflictDTO;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.dto.FreeSlotDTO;
import org.jarvis.lifetracker.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock
    private CalendarEventRepository calendarEventRepository;

    @Mock
    private DTOMapper dtoMapper;

    private CalendarService calendarService;

    @BeforeEach
    void setUp() {
        calendarService = new CalendarService(calendarEventRepository, dtoMapper);
    }

    @Test
    void moveEventRejectsEventsOwnedByAnotherUser() {
        when(calendarEventRepository.findByIdAndUserId(77L, "user-123")).thenReturn(Optional.empty());

        CalendarEventNotFoundException exception = assertThrows(
                CalendarEventNotFoundException.class,
                () -> calendarService.moveEvent(
                        "user-123",
                        77L,
                        LocalDateTime.of(2026, 3, 10, 12, 0),
                        LocalDateTime.of(2026, 3, 10, 13, 0)));

        assertEquals("user-123", exception.getUserId());
        assertEquals(77L, exception.getEventId());
        verify(calendarEventRepository, never()).save(any(CalendarEvent.class));
    }

    @Test
    void moveEventUsesUserScopedLookupAndPersistsUpdatedTimes() {
        CalendarEvent event = new CalendarEvent();
        event.setId(11L);
        event.setUserId("user-123");
        event.setTitle("Standup");
        event.setStartTime(LocalDateTime.of(2026, 3, 10, 9, 0));
        event.setEndTime(LocalDateTime.of(2026, 3, 10, 9, 30));

        CalendarEventDTO dto = new CalendarEventDTO(
                11L,
                "user-123",
                "Standup",
                null,
                LocalDateTime.of(2026, 3, 10, 11, 0),
                LocalDateTime.of(2026, 3, 10, 11, 30),
                false,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());

        when(calendarEventRepository.findByIdAndUserId(11L, "user-123")).thenReturn(Optional.of(event));
        when(calendarEventRepository.findByUserId("user-123")).thenReturn(java.util.List.of(event));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dtoMapper.toDTO(any(CalendarEvent.class))).thenReturn(dto);

        CalendarEventDTO result = calendarService.moveEvent(
                "user-123",
                11L,
                LocalDateTime.of(2026, 3, 10, 11, 0),
                LocalDateTime.of(2026, 3, 10, 11, 30));

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(calendarEventRepository).save(captor.capture());

        CalendarEvent saved = captor.getValue();
        assertEquals(LocalDateTime.of(2026, 3, 10, 11, 0), saved.getStartTime());
        assertEquals(LocalDateTime.of(2026, 3, 10, 11, 30), saved.getEndTime());
        assertEquals(11L, result.getId());
        verify(calendarEventRepository).findByIdAndUserId(11L, "user-123");
        verify(calendarEventRepository, never()).findById(eq(11L));
    }

    private CalendarEvent event(String userId, String title, LocalDateTime start, LocalDateTime end) {
        CalendarEvent event = new CalendarEvent();
        event.setUserId(userId);
        event.setTitle(title);
        event.setStartTime(start);
        event.setEndTime(end);
        return event;
    }

    @Test
    void createEventThrowsWhenUserIdBlank() {
        CalendarEvent event = event("  ", "Standup", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));

        assertThrows(IllegalArgumentException.class, () -> calendarService.createEvent(event));
        verify(calendarEventRepository, never()).save(any());
    }

    @Test
    void createEventThrowsWhenTitleBlank() {
        CalendarEvent event = event("user-1", " ", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));

        assertThrows(IllegalArgumentException.class, () -> calendarService.createEvent(event));
    }

    @Test
    void createEventThrowsWhenStartOrEndMissing() {
        CalendarEvent event = event("user-1", "Standup", LocalDateTime.of(2026, 3, 10, 9, 0), null);

        assertThrows(IllegalArgumentException.class, () -> calendarService.createEvent(event));
    }

    @Test
    void createEventThrowsWhenEndBeforeStart() {
        CalendarEvent event = event("user-1", "Standup", LocalDateTime.of(2026, 3, 10, 9, 30),
                LocalDateTime.of(2026, 3, 10, 9, 0));

        assertThrows(IllegalArgumentException.class, () -> calendarService.createEvent(event));
    }

    @Test
    void createEventDefaultsSourceToManualAndSavesWhenNoConflicts() {
        CalendarEvent event = event("user-1", "Standup", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of());
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDTO(any(CalendarEvent.class))).thenReturn(new CalendarEventDTO());

        calendarService.createEvent(event);

        ArgumentCaptor<CalendarEvent> captor = ArgumentCaptor.forClass(CalendarEvent.class);
        verify(calendarEventRepository).save(captor.capture());
        assertEquals(EntrySource.MANUAL, captor.getValue().getSource());
    }

    @Test
    void createEventThrowsConflictExceptionWhenOverlappingEventExists() {
        CalendarEvent candidate = event("user-1", "Standup", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));
        CalendarEvent existing = event("user-1", "Existing meeting", LocalDateTime.of(2026, 3, 10, 9, 15),
                LocalDateTime.of(2026, 3, 10, 9, 45));
        existing.setId(5L);
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(existing));

        CalendarConflictException ex = assertThrows(CalendarConflictException.class,
                () -> calendarService.createEvent(candidate));

        assertThat(ex.getConflicts()).hasSize(1);
        assertThat(ex.getConflicts().get(0).getEventId()).isEqualTo(5L);
        verify(calendarEventRepository, never()).save(any());
    }

    @Test
    void createEventRecurringDailyCandidateConflictsWithSingleExistingEventOnLaterDay() {
        CalendarEvent candidate = event("user-1", "Daily standup", LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 1, 9, 30));
        candidate.setRecurrenceRule("FREQ=DAILY;COUNT=5");

        CalendarEvent existing = event("user-1", "Dentist", LocalDateTime.of(2026, 3, 4, 9, 0),
                LocalDateTime.of(2026, 3, 4, 9, 30));
        existing.setId(9L);
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(existing));

        CalendarConflictException ex = assertThrows(CalendarConflictException.class,
                () -> calendarService.createEvent(candidate));

        assertThat(ex.getConflicts()).hasSize(1);
        assertThat(ex.getConflicts().get(0).getEventId()).isEqualTo(9L);
    }

    @Test
    void createEventRecurringWeeklyCandidateConflictsTwoWeeksLater() {
        CalendarEvent candidate = event("user-1", "Weekly sync", LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 1, 9, 30));
        candidate.setRecurrenceRule("FREQ=WEEKLY;COUNT=4");

        CalendarEvent existing = event("user-1", "Conference", LocalDateTime.of(2026, 3, 15, 9, 0),
                LocalDateTime.of(2026, 3, 15, 9, 30));
        existing.setId(3L);
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(existing));

        CalendarConflictException ex = assertThrows(CalendarConflictException.class,
                () -> calendarService.createEvent(candidate));

        assertThat(ex.getConflicts()).hasSize(1);
    }

    @Test
    void createEventRecurringMonthlyCandidateConflictsTwoMonthsLater() {
        CalendarEvent candidate = event("user-1", "Monthly review", LocalDateTime.of(2026, 1, 1, 9, 0),
                LocalDateTime.of(2026, 1, 1, 9, 30));
        candidate.setRecurrenceRule("FREQ=MONTHLY;COUNT=3");
        candidate.setRecurrenceUntil(LocalDateTime.of(2026, 4, 1, 9, 30));

        CalendarEvent existing = event("user-1", "Board meeting", LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 1, 9, 30));
        existing.setId(4L);
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(existing));

        CalendarConflictException ex = assertThrows(CalendarConflictException.class,
                () -> calendarService.createEvent(candidate));

        assertThat(ex.getConflicts()).hasSize(1);
    }

    @Test
    void createEventRecurringStopsAtExplicitRecurrenceUntilBeforeLaterConflict() {
        CalendarEvent candidate = event("user-1", "Daily standup", LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 1, 9, 30));
        candidate.setRecurrenceRule("FREQ=DAILY");
        candidate.setRecurrenceUntil(LocalDateTime.of(2026, 3, 2, 9, 30));

        CalendarEvent existing = event("user-1", "Dentist", LocalDateTime.of(2026, 3, 4, 9, 0),
                LocalDateTime.of(2026, 3, 4, 9, 30));
        existing.setId(9L);
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(existing));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDTO(any(CalendarEvent.class))).thenReturn(new CalendarEventDTO());

        calendarService.createEvent(candidate);

        verify(calendarEventRepository).save(any(CalendarEvent.class));
    }

    @Test
    void listEventsFiltersOutEventsOutsideRequestedRange() {
        CalendarEvent inRange = event("user-1", "In range", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));
        CalendarEvent tooEarly = event("user-1", "Too early", LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 1, 9, 30));
        CalendarEvent tooLate = event("user-1", "Too late", LocalDateTime.of(2026, 3, 20, 9, 0),
                LocalDateTime.of(2026, 3, 20, 9, 30));
        CalendarEvent noEndUsesDefaultDuration = event("user-1", "No end", LocalDateTime.of(2026, 3, 10, 23, 50), null);

        when(calendarEventRepository.findByUserId("user-1"))
                .thenReturn(List.of(inRange, tooEarly, tooLate, noEndUsesDefaultDuration));
        when(dtoMapper.toDTO(any(CalendarEvent.class))).thenAnswer(inv -> {
            CalendarEvent e = inv.getArgument(0);
            CalendarEventDTO dto = new CalendarEventDTO();
            dto.setTitle(e.getTitle());
            return dto;
        });

        List<CalendarEventDTO> result = calendarService.listEvents(
                "user-1", LocalDateTime.of(2026, 3, 5, 0, 0), LocalDateTime.of(2026, 3, 15, 0, 0));

        assertThat(result).extracting(CalendarEventDTO::getTitle)
                .containsExactlyInAnyOrder("In range", "No end");
    }

    @Test
    void listEventsWithNullFromAndToReturnsEverything() {
        CalendarEvent onlyEvent = event("user-1", "Some event", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(onlyEvent));
        when(dtoMapper.toDTO(any(CalendarEvent.class))).thenReturn(new CalendarEventDTO());

        List<CalendarEventDTO> result = calendarService.listEvents("user-1", null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findFreeSlotReturnsFirstSlotWhenNoBusyEvents() {
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of());

        FreeSlotDTO slot = calendarService.findFreeSlot(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 17, 0),
                30, null);

        assertThat(slot).isNotNull();
        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 0), slot.getStartTime());
        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 30), slot.getEndTime());
    }

    @Test
    void findFreeSlotSkipsOverlappingBusySlotAndReturnsNextAvailable() {
        CalendarEvent busy = event("user-1", "Busy", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 9, 30));
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(busy));

        FreeSlotDTO slot = calendarService.findFreeSlot(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 17, 0),
                30, null);

        assertThat(slot).isNotNull();
        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 30), slot.getStartTime());
        assertEquals(LocalDateTime.of(2026, 3, 10, 10, 0), slot.getEndTime());
    }

    @Test
    void findFreeSlotReturnsNullWhenNoSlotFitsBeforeWindowEnd() {
        CalendarEvent busy = event("user-1", "All day", LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 17, 0));
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of(busy));

        FreeSlotDTO slot = calendarService.findFreeSlot(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 9, 0),
                LocalDateTime.of(2026, 3, 10, 17, 0),
                30, null);

        assertNull(slot);
    }

    @Test
    void findFreeSlotAlignsCursorToWorkHoursStart() {
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of());
        CalendarService.WorkHours workHours = CalendarService.WorkHours.fromStrings("09:00", "17:00");

        FreeSlotDTO slot = calendarService.findFreeSlot(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 6, 0),
                LocalDateTime.of(2026, 3, 10, 17, 0),
                30, workHours);

        assertThat(slot).isNotNull();
        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 0), slot.getStartTime());
    }

    @Test
    void findFreeSlotAlignsCursorToNextDayWhenAfterWorkHoursEnd() {
        when(calendarEventRepository.findByUserId("user-1")).thenReturn(List.of());
        CalendarService.WorkHours workHours = CalendarService.WorkHours.fromStrings("09:00", "17:00");

        FreeSlotDTO slot = calendarService.findFreeSlot(
                "user-1",
                LocalDateTime.of(2026, 3, 10, 18, 0),
                LocalDateTime.of(2026, 3, 12, 17, 0),
                30, workHours);

        assertThat(slot).isNotNull();
        assertEquals(LocalDateTime.of(2026, 3, 11, 9, 0), slot.getStartTime());
    }

    @Test
    void workHoursFromStringsReturnsNullWhenEitherArgumentMissing() {
        assertNull(CalendarService.WorkHours.fromStrings(null, "17:00"));
        assertNull(CalendarService.WorkHours.fromStrings("09:00", null));
    }

    @Test
    void workHoursFromStringsParsesValidTimes() {
        CalendarService.WorkHours workHours = CalendarService.WorkHours.fromStrings("09:00", "17:30");

        assertEquals(LocalTime.of(9, 0), workHours.start());
        assertEquals(LocalTime.of(17, 30), workHours.end());
    }
}
