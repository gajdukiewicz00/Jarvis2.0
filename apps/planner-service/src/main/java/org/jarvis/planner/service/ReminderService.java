package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.model.ReminderStatus;
import org.jarvis.planner.repository.ReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Service for managing reminders
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {
    
    private final ReminderRepository reminderRepository;
    private final NotificationService notificationService;
    
    public List<Reminder> getActiveReminders(String userId) {
        return reminderRepository.findByUserIdAndStatus(userId, ReminderStatus.ACTIVE);
    }
    
    public List<Reminder> getUpcomingReminders(String userId, Instant start, Instant end) {
        return reminderRepository.findUpcomingReminders(userId, start, end);
    }
    
    @Transactional
    public Reminder createReminder(Reminder reminder) {
        reminder.setStatus(ReminderStatus.ACTIVE);
        Reminder saved = reminderRepository.save(reminder);
        log.info("Created reminder: {} for user: {}", saved.getId(), reminder.getUserId());
        return saved;
    }
    
    @Transactional
    public boolean triggerReminder(Long id) {
        Reminder reminder = reminderRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Reminder not found: " + id));

        NotificationService.NotificationDeliveryResult deliveryResult = notificationService.sendReminderNotification(
                reminder.getUserId(),
                reminder.getMessage());

        if (!deliveryResult.deliveredAny()) {
            log.warn("Reminder {} was due but no delivery channel accepted it; leaving ACTIVE for retry", id);
            return false;
        }

        reminder.setStatus(ReminderStatus.TRIGGERED);
        reminder.setTriggeredAt(Instant.now());
        reminderRepository.save(reminder);

        log.info("Triggered reminder: {} (desktop={}, voice={})",
                id,
                deliveryResult.desktopDelivered(),
                deliveryResult.voiceDelivered());
        return true;
    }
    
    public List<Reminder> checkDueReminders() {
        return reminderRepository.findDueReminders(Instant.now());
    }
}
