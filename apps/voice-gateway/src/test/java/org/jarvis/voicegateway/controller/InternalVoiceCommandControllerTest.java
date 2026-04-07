package org.jarvis.voicegateway.controller;

import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InternalVoiceCommandControllerTest {

    @Mock
    private PcControlActionGateway pcControlActionGateway;

    @Mock
    private OrchestratorClient orchestratorClient;

    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;

    @InjectMocks
    private InternalVoiceCommandController controller;

    @Test
    void dispatchPcActionRoutesToGateway() {
        when(pcControlActionGateway.dispatch("VOLUME_UP", Map.of("delta", 10), "user-42", null))
                .thenReturn(new PcControlActionGateway.DispatchResult(
                        "executed",
                        true,
                        true,
                        true,
                        false,
                        null,
                        Map.of("status", "executed")));

        ResponseEntity<?> response = controller.dispatchPcAction(Map.of(
                "action", "VOLUME_UP",
                "params", Map.of("delta", 10),
                "userId", "user-42"));

        assertEquals(200, response.getStatusCode().value());
        verify(pcControlActionGateway).dispatch("VOLUME_UP", Map.of("delta", 10), "user-42", null);
    }

    @Test
    void dispatchOrchestratorIntentRoutesToClient() {
        when(orchestratorClient.sendIntent("hello", Map.of(), "ru", "corr-123", "smoke", "user-42"))
                .thenReturn("privet");

        ResponseEntity<?> response = controller.dispatchOrchestratorIntent(Map.of(
                "intent", "hello",
                "language", "ru",
                "correlationId", "corr-123",
                "originalText", "smoke",
                "userId", "user-42"));

        assertEquals(200, response.getStatusCode().value());
        verify(orchestratorClient).sendIntent("hello", Map.of(), "ru", "corr-123", "smoke", "user-42");
    }

    @Test
    void dispatchSmartHomeActionRoutesToGateway() {
        ResponseEntity<?> response = controller.dispatchSmartHomeAction(Map.of(
                "deviceId", "kitchen_light",
                "action", "TURN_ON",
                "payload", "warm_white",
                "userId", "user-42"));

        assertEquals(200, response.getStatusCode().value());
        verify(smartHomeActionGateway).execute("user-42", "kitchen_light", "TURN_ON", "warm_white");
    }
}
