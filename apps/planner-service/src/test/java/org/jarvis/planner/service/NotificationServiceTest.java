package org.jarvis.planner.service;

import org.jarvis.planner.client.DesktopNotificationClient;
import org.jarvis.planner.client.VoiceNotificationClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private DesktopNotificationClient desktopNotificationClient;

    @Mock
    private VoiceNotificationClient voiceNotificationClient;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void sendReminderNotificationReturnsDeliveredWhenAnyChannelAcceptsReminder() {
        when(desktopNotificationClient.sendNotification("user-1", "Напоминание", "Время размяться")).thenReturn(true);
        when(voiceNotificationClient.sendNotification("user-1", "Время размяться", "ru-RU")).thenReturn(false);

        NotificationService.NotificationDeliveryResult result = notificationService.sendReminderNotification(
                "user-1",
                "Время размяться");

        assertTrue(result.desktopDelivered());
        assertFalse(result.voiceDelivered());
        assertTrue(result.deliveredAny());
    }

    @Test
    void sendReminderNotificationContinuesWhenDesktopChannelThrows() {
        when(desktopNotificationClient.sendNotification("user-2", "Напоминание", "Пора сделать перерыв"))
                .thenThrow(new IllegalStateException("gateway unavailable"));
        when(voiceNotificationClient.sendNotification("user-2", "Пора сделать перерыв", "ru-RU")).thenReturn(true);

        NotificationService.NotificationDeliveryResult result = notificationService.sendReminderNotification(
                "user-2",
                "Пора сделать перерыв");

        assertFalse(result.desktopDelivered());
        assertTrue(result.voiceDelivered());
        assertTrue(result.deliveredAny());
        verify(voiceNotificationClient).sendNotification("user-2", "Пора сделать перерыв", "ru-RU");
    }
}
