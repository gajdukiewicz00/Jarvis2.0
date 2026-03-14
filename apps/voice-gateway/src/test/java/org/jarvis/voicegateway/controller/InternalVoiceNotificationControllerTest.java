package org.jarvis.voicegateway.controller;

import org.jarvis.voicegateway.websocket.VoiceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalVoiceNotificationControllerTest {

    @Mock
    private VoiceWebSocketHandler voiceWebSocketHandler;

    @InjectMocks
    private InternalVoiceNotificationController controller;

    @Test
    void sendNotificationRoutesToConnectedUserSessions() {
        when(voiceWebSocketHandler.sendNotificationToUser("user-9", "Пора выпить воды", "ru-RU")).thenReturn(1);

        ResponseEntity<?> response = controller.sendNotification(Map.of(
                "userId", "user-9",
                "message", "Пора выпить воды",
                "languageCode", "ru-RU"));

        assertEquals(200, response.getStatusCode().value());
        verify(voiceWebSocketHandler).sendNotificationToUser(eq("user-9"), eq("Пора выпить воды"), eq("ru-RU"));
    }
}
