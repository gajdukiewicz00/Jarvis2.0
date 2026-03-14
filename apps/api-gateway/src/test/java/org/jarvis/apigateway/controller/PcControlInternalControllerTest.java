package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PcControlInternalControllerTest {

    @Mock
    private PcControlWebSocketHandler webSocketHandler;

    @InjectMocks
    private PcControlInternalController controller;

    @Test
    void sendActionRoutesToSpecificUserWhenUserIdIsProvided() {
        when(webSocketHandler.hasConnectedClients()).thenReturn(true);
        when(webSocketHandler.sendPcActionToUser(eq("user-42"), eq("NOTIFY"), any())).thenReturn(1);

        ResponseEntity<?> response = controller.sendAction(Map.of(
                "action", "NOTIFY",
                "userId", "user-42",
                "params", Map.of("message", "Planner reminder")));

        assertEquals(200, response.getStatusCode().value());
        verify(webSocketHandler).sendPcActionToUser(eq("user-42"), eq("NOTIFY"), any());
    }
}
