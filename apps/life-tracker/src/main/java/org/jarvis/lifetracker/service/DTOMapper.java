package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.ExpenseDraft;
import org.jarvis.lifetracker.domain.TimeRecord;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.ExpenseDraftDTO;
import org.jarvis.lifetracker.dto.TimeRecordDTO;
import org.springframework.stereotype.Service;

/**
 * Maps between domain entities and DTOs
 */
@Service
public class DTOMapper {

    public ExpenseDTO toDTO(Expense entity) {
        if (entity == null)
            return null;
        return new ExpenseDTO(
                entity.getId(),
                entity.getUserId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getCategory(),
                entity.getDescription(),
                entity.getType(),
                entity.getMerchant(),
                entity.getPaymentMethod(),
                entity.getOccurredAt(),
                entity.getSource(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public ExpenseDraftDTO toDTO(ExpenseDraft entity) {
        if (entity == null)
            return null;
        return new ExpenseDraftDTO(
                entity.getId(),
                entity.getUserId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getCategory(),
                entity.getMerchant(),
                entity.getType(),
                entity.getPaymentMethod(),
                entity.getOccurredAt(),
                entity.getConfidence(),
                entity.getNotes(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public TimeRecordDTO toDTO(TimeRecord entity) {
        if (entity == null)
            return null;
        return new TimeRecordDTO(
                entity.getId(),
                entity.getActivity(),
                entity.getCategory(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getDurationSeconds());
    }

    public CalendarEventDTO toDTO(CalendarEvent entity) {
        if (entity == null)
            return null;
        return new CalendarEventDTO(
                entity.getId(),
                entity.getUserId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.isAllDay(),
                entity.getLocation(),
                entity.getRecurrenceRule(),
                entity.getRecurrenceUntil(),
                entity.getTimezone(),
                entity.getSource(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
