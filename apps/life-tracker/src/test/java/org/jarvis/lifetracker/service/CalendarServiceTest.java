package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
