package org.jarvis.planner.service;

import org.jarvis.planner.client.AnalyticsClient;
import org.jarvis.planner.client.PcControlActionClient;
import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.model.ReminderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoActionServiceTest {

    @Mock
    private AnalyticsClient analyticsClient;

    @Mock
    private NotificationService notificationService;

    @Mock
    private PcControlActionClient pcControlActionClient;

    @Mock
    private ReminderService reminderService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T10:15:00Z"), ZoneOffset.UTC);

    private AutoActionService autoActionService;

    @BeforeEach
    void setUp() {
        autoActionService = new AutoActionService(
                analyticsClient,
                notificationService,
                pcControlActionClient,
                reminderService,
                clock);
    }

    @Test
    void triggerFocusModeRoutesMappedScenarioToDesktop() {
        when(pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"))).thenReturn(true);

        autoActionService.triggerFocusMode("user-1", "FOCUS");

        verify(pcControlActionClient).sendAction("user-1", "SCENARIO", Map.of("name", "focus"));
        verify(notificationService).sendDesktopNotification(
                "user-1",
                "Focus Mode",
                "Режим фокусировки 'FOCUS' активирован");
    }

    @Test
    void startPomodoroTimerSchedulesBreakReminderAndEnablesFocusScenario() {
        when(pcControlActionClient.sendAction("user-7", "SCENARIO", Map.of("name", "focus"))).thenReturn(true);

        autoActionService.startPomodoroTimer("user-7", 25);

        verify(pcControlActionClient).sendAction("user-7", "SCENARIO", Map.of("name", "focus"));
        verify(notificationService).sendDesktopNotification(
                "user-7",
                "Pomodoro",
                "Таймер на 25 минут запущен. Фокус!");

        ArgumentCaptor<Reminder> reminderCaptor = ArgumentCaptor.forClass(Reminder.class);
        verify(reminderService).createReminder(reminderCaptor.capture());
        Reminder reminder = reminderCaptor.getValue();
        assertEquals("user-7", reminder.getUserId());
        assertEquals("Pomodoro завершен. Сделай перерыв на 5 минут.", reminder.getMessage());
        assertEquals(Instant.parse("2026-03-13T10:40:00Z"), reminder.getReminderTime());
        assertEquals(ReminderType.ONCE, reminder.getReminderType());
    }

    @Test
    void startMusicPlaylistRoutesScenarioInsteadOfPretendingToControlMusic() {
        when(pcControlActionClient.sendAction("user-9", "SCENARIO", Map.of("name", "party"))).thenReturn(true);

        autoActionService.startMusicPlaylist("user-9", "PARTY");

        verify(pcControlActionClient).sendAction("user-9", "SCENARIO", Map.of("name", "party"));
        verify(notificationService).sendVoiceNotification("user-9", "Включаю плейлист для PARTY");
    }

    @Test
    void triggerFocusModeWithWorkModeMapsToWorkScenario() {
        when(pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "work"))).thenReturn(true);

        autoActionService.triggerFocusMode("user-1", "WORK");

        verify(pcControlActionClient).sendAction("user-1", "SCENARIO", Map.of("name", "work"));
    }

    @Test
    void triggerFocusModeWithRelaxModeMapsToRestScenario() {
        when(pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "rest"))).thenReturn(true);

        autoActionService.triggerFocusMode("user-1", "RELAX");

        verify(pcControlActionClient).sendAction("user-1", "SCENARIO", Map.of("name", "rest"));
    }

    @Test
    void triggerFocusModeWithUnknownModeLowercasesTheScenarioName() {
        when(pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "zen"))).thenReturn(true);

        autoActionService.triggerFocusMode("user-1", "Zen");

        verify(pcControlActionClient).sendAction("user-1", "SCENARIO", Map.of("name", "zen"));
    }

    @Test
    void triggerFocusModeWithNullModeDefaultsToFocusScenario() {
        when(pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"))).thenReturn(true);

        autoActionService.triggerFocusMode("user-1", null);

        verify(pcControlActionClient).sendAction("user-1", "SCENARIO", Map.of("name", "focus"));
    }

    @Test
    void triggerFocusModeStillNotifiesWhenRoutingToDesktopFails() {
        when(pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"))).thenReturn(false);

        autoActionService.triggerFocusMode("user-1", "FOCUS");

        verify(notificationService).sendDesktopNotification(
                "user-1",
                "Focus Mode",
                "Режим фокусировки 'FOCUS' активирован");
    }

    @Test
    void startMusicPlaylistWithRelaxTypeMapsToRestScenario() {
        when(pcControlActionClient.sendAction("user-9", "SCENARIO", Map.of("name", "rest"))).thenReturn(true);

        autoActionService.startMusicPlaylist("user-9", "RELAX");

        verify(pcControlActionClient).sendAction("user-9", "SCENARIO", Map.of("name", "rest"));
    }

    @Test
    void startMusicPlaylistWithMorningTypeMapsToMorningScenario() {
        when(pcControlActionClient.sendAction("user-9", "SCENARIO", Map.of("name", "morning"))).thenReturn(true);

        autoActionService.startMusicPlaylist("user-9", "MORNING");

        verify(pcControlActionClient).sendAction("user-9", "SCENARIO", Map.of("name", "morning"));
    }

    @Test
    void startMusicPlaylistWithBlankTypeDefaultsToFocusScenario() {
        when(pcControlActionClient.sendAction("user-9", "SCENARIO", Map.of("name", "focus"))).thenReturn(true);

        autoActionService.startMusicPlaylist("user-9", "   ");

        verify(pcControlActionClient).sendAction("user-9", "SCENARIO", Map.of("name", "focus"));
    }

    @Test
    void startMusicPlaylistWithUnrecognizedTypeDefaultsToFocusScenario() {
        when(pcControlActionClient.sendAction("user-9", "SCENARIO", Map.of("name", "focus"))).thenReturn(true);

        autoActionService.startMusicPlaylist("user-9", "SOMETHING_ELSE");

        verify(pcControlActionClient).sendAction("user-9", "SCENARIO", Map.of("name", "focus"));
    }

    @Test
    void suggestBreakSendsDesktopAndVoiceNotifications() {
        autoActionService.suggestBreak("user-3");

        verify(notificationService).sendDesktopNotification(
                "user-3",
                "Перерыв",
                "Пора отдохнуть! Прогулка 10 минут или разминка?");
        verify(notificationService).sendVoiceNotification(
                "user-3",
                "Ты долго работаешь. Предлагаю сделать перерыв на 10 минут.");
    }

    @Test
    void checkAndTriggerActionsDoesNothingWhenSchedulerDisabled() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", false);

        autoActionService.checkAndTriggerActions();

        verifyNoInteractions(analyticsClient, pcControlActionClient);
    }

    @Test
    void checkAndTriggerActionsDoesNothingWhenScheduledUserIdBlank() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", true);
        ReflectionTestUtils.setField(autoActionService, "scheduledUserId", "   ");

        autoActionService.checkAndTriggerActions();

        verifyNoInteractions(analyticsClient, pcControlActionClient);
    }

    @Test
    void checkAndTriggerActionsTriggersFocusModeAndSleepNotificationWhenThresholdsBreached() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", true);
        ReflectionTestUtils.setField(autoActionService, "scheduledUserId", "user-42");

        when(analyticsClient.getAverageSleepHours("user-42")).thenReturn(5.0);
        when(analyticsClient.getWeeklyOvertimeHours("user-42")).thenReturn(11);
        when(pcControlActionClient.sendAction(eq("user-42"), eq("SCENARIO"), any())).thenReturn(true);

        autoActionService.checkAndTriggerActions();

        verify(notificationService).sendDesktopNotification(
                eq("user-42"), eq("Здоровье"), any());
        verify(notificationService).sendDesktopNotification(
                eq("user-42"), eq("Focus Mode"), any());
        verify(notificationService).sendDesktopNotification(
                eq("user-42"), eq("Перерыв"), any());
    }

    @Test
    void checkAndTriggerActionsSwallowsDataAccessException() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", true);
        ReflectionTestUtils.setField(autoActionService, "scheduledUserId", "user-42");

        when(analyticsClient.getAverageSleepHours("user-42"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> autoActionService.checkAndTriggerActions());
    }

    @Test
    void checkAndTriggerActionsSwallowsGenericRuntimeException() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", true);
        ReflectionTestUtils.setField(autoActionService, "scheduledUserId", "user-42");

        when(analyticsClient.getAverageSleepHours("user-42")).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> autoActionService.checkAndTriggerActions());
    }

    @Test
    void checkBreakRemindersDoesNothingWhenSchedulerDisabled() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", false);

        assertDoesNotThrow(() -> autoActionService.checkBreakReminders());

        verifyNoInteractions(notificationService, reminderService);
    }

    @Test
    void checkBreakRemindersRunsWhenSchedulerEnabled() {
        ReflectionTestUtils.setField(autoActionService, "schedulerEnabled", true);
        ReflectionTestUtils.setField(autoActionService, "scheduledUserId", "user-42");

        assertDoesNotThrow(() -> autoActionService.checkBreakReminders());
    }
}
