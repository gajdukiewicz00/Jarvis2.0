package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TimeRecord;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.TimeRecordDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DTOMapperTest {

    private final DTOMapper mapper = new DTOMapper();

    @Test
    void toExpenseDtoMapsAllFields() {
        Expense expense = new Expense();
        expense.setId(1L);
        expense.setUserId("user-1");
        expense.setAmount(new BigDecimal("19.99"));
        expense.setCurrency("USD");
        expense.setCategory("food");
        expense.setDescription("Lunch");
        expense.setType(TransactionType.EXPENSE);
        expense.setMerchant("Cafe");
        expense.setPaymentMethod("card");
        expense.setOccurredAt(LocalDateTime.of(2026, 3, 8, 12, 30));
        expense.setSource(EntrySource.MANUAL);
        expense.setCreatedAt(Instant.parse("2026-03-08T10:00:00Z"));
        expense.setUpdatedAt(Instant.parse("2026-03-08T10:05:00Z"));

        ExpenseDTO dto = mapper.toDTO(expense);

        assertEquals(1L, dto.getId());
        assertEquals("user-1", dto.getUserId());
        assertEquals(new BigDecimal("19.99"), dto.getAmount());
        assertEquals("USD", dto.getCurrency());
        assertEquals("food", dto.getCategory());
        assertEquals("Lunch", dto.getDescription());
        assertEquals(TransactionType.EXPENSE, dto.getType());
        assertEquals("Cafe", dto.getMerchant());
        assertEquals("card", dto.getPaymentMethod());
        assertEquals(LocalDateTime.of(2026, 3, 8, 12, 30), dto.getOccurredAt());
        assertEquals(EntrySource.MANUAL, dto.getSource());
        assertEquals(Instant.parse("2026-03-08T10:00:00Z"), dto.getCreatedAt());
        assertEquals(Instant.parse("2026-03-08T10:05:00Z"), dto.getUpdatedAt());
    }

    @Test
    void toTimeRecordDtoMapsCoreFields() {
        TimeRecord record = new TimeRecord();
        record.setId(2L);
        record.setActivity("Deep work");
        record.setCategory("work");
        record.setStartTime(LocalDateTime.of(2026, 3, 8, 9, 0));
        record.setEndTime(LocalDateTime.of(2026, 3, 8, 11, 0));
        record.setDurationSeconds(7200L);

        TimeRecordDTO dto = mapper.toDTO(record);

        assertEquals(2L, dto.getId());
        assertEquals("Deep work", dto.getActivity());
        assertEquals("work", dto.getCategory());
        assertEquals(LocalDateTime.of(2026, 3, 8, 9, 0), dto.getStartTime());
        assertEquals(LocalDateTime.of(2026, 3, 8, 11, 0), dto.getEndTime());
        assertEquals(7200L, dto.getDurationSeconds());
    }

    @Test
    void toCalendarEventDtoMapsSchedulingFields() {
        CalendarEvent event = new CalendarEvent();
        event.setId(3L);
        event.setUserId("user-2");
        event.setTitle("Planning");
        event.setDescription("Sprint planning");
        event.setStartTime(LocalDateTime.of(2026, 3, 9, 14, 0));
        event.setEndTime(LocalDateTime.of(2026, 3, 9, 15, 0));
        event.setAllDay(false);
        event.setLocation("Meeting room");
        event.setRecurrenceRule("FREQ=WEEKLY");
        event.setRecurrenceUntil(LocalDateTime.of(2026, 4, 1, 0, 0));
        event.setTimezone("Europe/Warsaw");
        event.setSource(EntrySource.MANUAL);
        event.setCreatedAt(Instant.parse("2026-03-08T11:00:00Z"));
        event.setUpdatedAt(Instant.parse("2026-03-08T11:05:00Z"));

        CalendarEventDTO dto = mapper.toDTO(event);

        assertEquals(3L, dto.getId());
        assertEquals("user-2", dto.getUserId());
        assertEquals("Planning", dto.getTitle());
        assertEquals("Sprint planning", dto.getDescription());
        assertEquals(LocalDateTime.of(2026, 3, 9, 14, 0), dto.getStartTime());
        assertEquals(LocalDateTime.of(2026, 3, 9, 15, 0), dto.getEndTime());
        assertEquals(false, dto.isAllDay());
        assertEquals("Meeting room", dto.getLocation());
        assertEquals("FREQ=WEEKLY", dto.getRecurrenceRule());
        assertEquals(LocalDateTime.of(2026, 4, 1, 0, 0), dto.getRecurrenceUntil());
        assertEquals("Europe/Warsaw", dto.getTimezone());
        assertEquals(EntrySource.MANUAL, dto.getSource());
        assertEquals(Instant.parse("2026-03-08T11:00:00Z"), dto.getCreatedAt());
        assertEquals(Instant.parse("2026-03-08T11:05:00Z"), dto.getUpdatedAt());
    }

    @Test
    void toDtoReturnsNullForNullEntities() {
        assertNull(mapper.toDTO((Expense) null));
        assertNull(mapper.toDTO((TimeRecord) null));
        assertNull(mapper.toDTO((CalendarEvent) null));
    }
}
