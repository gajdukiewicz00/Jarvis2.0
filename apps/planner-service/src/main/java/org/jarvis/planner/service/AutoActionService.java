package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.client.AnalyticsClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Automatic actions triggered based on analytics and user habits
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoActionService {

    private final AnalyticsClient analyticsClient;
    private final NotificationService notificationService;

    /**
     * Check every hour for automatic actions
     * Wrapped in try-catch to prevent scheduler death on database connection
     * failures
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at :00
    public void checkAndTriggerActions() {
        try {
            log.debug("Checking for automatic actions...");

            String userId = "denis"; // TODO: Multi-user support

            // Get analytics data
            Double avgSleep = analyticsClient.getAverageSleepHours(userId);
            Integer overtime = analyticsClient.getWeeklyOvertimeHours(userId);

            // Trigger actions based on conditions
            if (overtime != null && overtime > 10) {
                triggerFocusMode(userId, "RELAX");
                suggestBreak(userId);
            }

            if (avgSleep != null && avgSleep < 6.5) {
                notificationService.sendDesktopNotification(
                        userId,
                        "Здоровье",
                        "Ты мало спишь. Попробуй лечь пораньше сегодня.");
            }
        } catch (org.springframework.dao.DataAccessException e) {
            // Database connection failure - log and continue, will retry in 1 hour
            log.error("Database connection error during auto-action check: {}. Will retry in 1 hour.",
                    e.getMessage());
        } catch (Exception e) {
            // Catch all other exceptions to prevent scheduler from stopping
            log.error("Unexpected error during auto-action check: {}", e.getMessage(), e);
        }
    }

    /**
     * Trigger focus mode
     */
    public void triggerFocusMode(String userId, String mode) {
        log.info("Activating focus mode: {} for user: {}", mode, userId);

        // TODO: Integrate with pc-control to:
        // - Close distracting apps (social media, games)
        // - Enable Do Not Disturb
        // - Set screen dimming

        notificationService.sendDesktopNotification(
                userId,
                "Focus Mode",
                "Режим фокусировки '" + mode + "' активирован");
    }

    /**
     * Start music playlist
     */
    public void startMusicPlaylist(String userId, String playlistType) {
        log.info("Starting {} playlist for user: {}", playlistType, userId);

        // TODO: Integrate with music player (Spotify API, local player)
        // playlistType: "WORK", "RELAX", "FOCUS", "WORKOUT"

        notificationService.sendVoiceNotification(
                userId,
                "Включаю плейлист для " + playlistType);
    }

    /**
     * Auto-start Pomodoro timer
     */
    public void startPomodoroTimer(String userId, int durationMinutes) {
        log.info("Starting Pomodoro timer ({} min) for user: {}", durationMinutes, userId);

        // TODO: Integrate with pc-control timer service
        // Start 25-minute work session
        // Auto-start 5-minute break after

        notificationService.sendDesktopNotification(
                userId,
                "Pomodoro",
                "Таймер на " + durationMinutes + " минут запущен. Фокус!");
    }

    /**
     * Suggest break reminder
     */
    public void suggestBreak(String userId) {
        log.info("Suggesting break for user: {}", userId);

        notificationService.sendDesktopNotification(
                userId,
                "Перерыв",
                "Пора отдохнуть! Прогулка 10 минут или разминка?");

        notificationService.sendVoiceNotification(
                userId,
                "Ты долго работаешь. Предлагаю сделать перерыв на 10 минут.");
    }

    /**
     * Check every hour if user needs a break
     * Wrapped in try-catch to prevent scheduler death on errors
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void checkBreakReminders() {
        try {
            String userId = "denis";

            // TODO: Track user active time via pc-control
            // If active > 2 hours continuously → suggest break

            log.debug("Checking break reminders for user: {}", userId);
        } catch (Exception e) {
            // Catch all exceptions to prevent scheduler from stopping
            log.error("Error during break reminder check: {}", e.getMessage(), e);
        }
    }
}
