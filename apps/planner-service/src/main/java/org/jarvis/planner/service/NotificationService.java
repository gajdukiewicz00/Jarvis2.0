package org.jarvis.planner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Notification system for sending alerts to desktop/voice
 * Placeholder for future integration with desktop-client and voice-gateway
 */
@Slf4j
@Service
public class NotificationService {
    
    /**
     * Send notification to desktop client
     */
    public void sendDesktopNotification(String userId, String title, String message) {
        log.info("Desktop notification for {}: {} - {}", userId, title, message);
        // TODO: Integrate with desktop-client WebSocket/REST API
    }
    
    /**
     * Send voice notification
     */
    public void sendVoiceNotification(String userId, String message) {
        log.info("Voice notification for {}: {}", userId, message);
        // TODO: Integrate with voice-gateway TTS
    }
    
    /**
     * Send reminder notification
     */
    public void sendReminderNotification(String userId, String reminderMessage) {
        log.info("Reminder notification for {}: {}", userId, reminderMessage);
        sendDesktopNotification(userId, "Напоминание", reminderMessage);
        sendVoiceNotification(userId, reminderMessage);
    }
}
