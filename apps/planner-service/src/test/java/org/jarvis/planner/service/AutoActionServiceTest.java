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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
}
