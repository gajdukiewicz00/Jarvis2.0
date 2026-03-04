package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.Reminder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Scheduler for checking and triggering due reminders
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderScheduler {

    private final ReminderService reminderService;

    /**
     * Check for due reminders every minute
     * Wrapped in try-catch to prevent scheduler death on database connection
     * failures
     */
    @Scheduled(fixedRate = 60000) // Every 60 seconds
    public void checkReminders() {
        try {
            List<Reminder> dueReminders = reminderService.checkDueReminders();

            if (!dueReminders.isEmpty()) {
                log.info("Found {} due reminders", dueReminders.size());

                for (Reminder reminder : dueReminders) {
                    try {
                        reminderService.triggerReminder(reminder.getId());
                        log.info("Triggered reminder: {} - {}", reminder.getId(), reminder.getMessage());
                    } catch (RuntimeException e) {
                        log.error("Error triggering reminder {}: {}", reminder.getId(), e.getMessage());
                    }
                }
            }
        } catch (org.springframework.dao.DataAccessException e) {
            // Database connection failure - log and continue, will retry on next schedule
            log.error("Database connection error while checking reminders: {}. Will retry in 60 seconds.",
                    e.getMessage());
        } catch (RuntimeException e) {
            // Catch all other exceptions to prevent scheduler from stopping
            log.error("Unexpected error while checking reminders: {}", e.getMessage(), e);
        }
    }
}
