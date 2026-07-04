package org.jarvis.planner.service;

import org.jarvis.planner.model.Reminder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderSchedulerTest {

    @Mock
    private ReminderService reminderService;

    private ReminderScheduler reminderScheduler;

    @BeforeEach
    void setUp() {
        reminderScheduler = new ReminderScheduler(reminderService);
    }

    private Reminder reminder(Long id, String message) {
        Reminder reminder = new Reminder();
        reminder.setId(id);
        reminder.setMessage(message);
        return reminder;
    }

    @Test
    void checkRemindersDoesNothingWhenNoneAreDue() {
        when(reminderService.checkDueReminders()).thenReturn(List.of());

        reminderScheduler.checkReminders();

        verify(reminderService).checkDueReminders();
    }

    @Test
    void checkRemindersTriggersEachDueReminderAndLogsDelivery() {
        Reminder delivered = reminder(1L, "Call mom");
        Reminder notDelivered = reminder(2L, "Water plants");
        when(reminderService.checkDueReminders()).thenReturn(List.of(delivered, notDelivered));
        when(reminderService.triggerReminder(1L)).thenReturn(true);
        when(reminderService.triggerReminder(2L)).thenReturn(false);

        reminderScheduler.checkReminders();

        verify(reminderService).triggerReminder(1L);
        verify(reminderService).triggerReminder(2L);
    }

    @Test
    void checkRemindersContinuesWhenTriggeringOneReminderThrows() {
        Reminder broken = reminder(3L, "Broken reminder");
        Reminder healthy = reminder(4L, "Healthy reminder");
        when(reminderService.checkDueReminders()).thenReturn(List.of(broken, healthy));
        when(reminderService.triggerReminder(3L)).thenThrow(new RuntimeException("boom"));
        when(reminderService.triggerReminder(4L)).thenReturn(true);

        assertDoesNotThrow(() -> reminderScheduler.checkReminders());

        verify(reminderService).triggerReminder(3L);
        verify(reminderService).triggerReminder(4L);
    }

    @Test
    void checkRemindersSwallowsDataAccessExceptionFromRepository() {
        when(reminderService.checkDueReminders()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> reminderScheduler.checkReminders());
    }

    @Test
    void checkRemindersSwallowsGenericRuntimeExceptionFromRepository() {
        when(reminderService.checkDueReminders()).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> reminderScheduler.checkReminders());
    }
}
