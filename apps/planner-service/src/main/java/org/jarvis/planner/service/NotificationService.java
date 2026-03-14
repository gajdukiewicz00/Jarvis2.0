package org.jarvis.planner.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.client.DesktopNotificationClient;
import org.jarvis.planner.client.VoiceNotificationClient;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * Notification system for sending alerts to desktop and voice channels.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String DEFAULT_VOICE_LANGUAGE = "ru-RU";
    private final DesktopNotificationClient desktopNotificationClient;
    private final VoiceNotificationClient voiceNotificationClient;

    public record NotificationDeliveryResult(boolean desktopDelivered, boolean voiceDelivered) {
        public boolean deliveredAny() {
            return desktopDelivered || voiceDelivered;
        }
    }
    
    /**
     * Send notification to desktop client
     */
    public boolean sendDesktopNotification(String userId, String title, String message) {
        log.info("Desktop notification for {}: {} - {}", userId, title, message);
        return desktopNotificationClient.sendNotification(userId, title, message);
    }
    
    /**
     * Send voice notification
     */
    public boolean sendVoiceNotification(String userId, String message) {
        log.info("Voice notification for {}: {}", userId, message);
        return voiceNotificationClient.sendNotification(userId, message, DEFAULT_VOICE_LANGUAGE);
    }
    
    /**
     * Send reminder notification
     */
    public NotificationDeliveryResult sendReminderNotification(String userId, String reminderMessage) {
        log.info("Reminder notification for {}: {}", userId, reminderMessage);
        boolean desktopDelivered = invokeSafely(
                () -> sendDesktopNotification(userId, "Напоминание", reminderMessage),
                "desktop",
                userId);
        boolean voiceDelivered = invokeSafely(
                () -> sendVoiceNotification(userId, reminderMessage),
                "voice",
                userId);
        return new NotificationDeliveryResult(desktopDelivered, voiceDelivered);
    }

    private boolean invokeSafely(NotificationOperation operation, String channel, String userId) {
        try {
            return operation.execute();
        } catch (RuntimeException e) {
            log.warn("Notification channel {} failed for user {}: {}", channel, userId, e.getMessage());
            return false;
        }
    }

    @FunctionalInterface
    private interface NotificationOperation {
        boolean execute();
    }
}
