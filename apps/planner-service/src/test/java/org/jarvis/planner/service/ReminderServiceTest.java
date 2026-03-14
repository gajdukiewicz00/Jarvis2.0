package org.jarvis.planner.service;

import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.model.ReminderStatus;
import org.jarvis.planner.repository.ReminderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    @Mock
    private ReminderRepository reminderRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ReminderService reminderService;

    @Test
    void triggerReminderMarksReminderTriggeredAfterSuccessfulDelivery() {
        Reminder reminder = reminder("user-1", "Перерыв через пять минут");
        when(reminderRepository.findById(10L)).thenReturn(Optional.of(reminder));
        when(notificationService.sendReminderNotification("user-1", "Перерыв через пять минут"))
                .thenReturn(new NotificationService.NotificationDeliveryResult(true, false));

        boolean delivered = reminderService.triggerReminder(10L);

        assertTrue(delivered);
        ArgumentCaptor<Reminder> savedReminder = ArgumentCaptor.forClass(Reminder.class);
        verify(reminderRepository).save(savedReminder.capture());
        assertEquals(ReminderStatus.TRIGGERED, savedReminder.getValue().getStatus());
        assertNotNull(savedReminder.getValue().getTriggeredAt());
    }

    @Test
    void triggerReminderKeepsReminderActiveWhenAllChannelsRejectDelivery() {
        Reminder reminder = reminder("user-2", "Пора встать");
        when(reminderRepository.findById(11L)).thenReturn(Optional.of(reminder));
        when(notificationService.sendReminderNotification("user-2", "Пора встать"))
                .thenReturn(new NotificationService.NotificationDeliveryResult(false, false));

        boolean delivered = reminderService.triggerReminder(11L);

        assertEquals(ReminderStatus.ACTIVE, reminder.getStatus());
        assertEquals(null, reminder.getTriggeredAt());
        assertTrue(!delivered);
        verify(reminderRepository, never()).save(reminder);
    }

    private Reminder reminder(String userId, String message) {
        Reminder reminder = new Reminder();
        reminder.setId(10L);
        reminder.setUserId(userId);
        reminder.setMessage(message);
        reminder.setReminderTime(Instant.parse("2026-03-13T12:00:00Z"));
        reminder.setStatus(ReminderStatus.ACTIVE);
        return reminder;
    }
}
