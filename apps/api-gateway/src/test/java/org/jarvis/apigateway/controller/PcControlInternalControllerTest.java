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
import static org.mockito.ArgumentMatchers.argThat;
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
        when(webSocketHandler.dispatchPcAction(
                eq("NOTIFY"),
                argThat(jsonNode -> "Planner reminder".equals(jsonNode.path("message").asText())),
                eq(null),
                eq("user-42"),
                eq(null)))
                .thenReturn(new PcControlWebSocketHandler.DispatchResult(
                        "req-1",
                        "NOTIFY",
                        "executed",
                        true,
                        true,
                        true,
                        false,
                        null,
                        1,
                        1,
                        1,
                        0,
                        null,
                        "user-42"));

        ResponseEntity<?> response = controller.sendAction(Map.of(
                "action", "NOTIFY",
                "userId", "user-42",
                "params", Map.of("message", "Planner reminder")));

        assertEquals(200, response.getStatusCode().value());
        verify(webSocketHandler).dispatchPcAction(
                eq("NOTIFY"),
                argThat(jsonNode -> "Planner reminder".equals(jsonNode.path("message").asText())),
                eq(null),
                eq("user-42"),
                eq(null));
    }
}
